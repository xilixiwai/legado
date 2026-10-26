package io.legado.app.help.book

/**
 * 「批量替换域名」冲突分类与处理规则的**纯逻辑核心**。
 *
 * 数据模型事实（决定分类的约束，来自 Room 导出的生产 schema）：
 * - `books` 主键是 `bookUrl`，另有 **UNIQUE(name, author)** 唯一索引；
 * - `chapters` 主键是 `(url, bookUrl)`，外键 `bookUrl → books.bookUrl` **ON DELETE CASCADE**；
 * - 缓存目录名 = 书名前 9 字符 + MD5(bookUrl)（[getFolderNameNoCache]）。
 *
 * 由此推出的分类依据：
 * 1. 能共存的旧/新两条书源记录 **必然 name 或 author 文本有差异**（唯一索引所迫），
 *    所以「同一本书」的判定不能依赖书名作者完全相等，必须靠结构证据：
 *    同一 book source（`origin` 相同）+ 章节路径重叠或 bookUrl 路径相同。
 * 2. 本地书籍的 `bookUrl` 不是 http(s) 地址：既不会被替换规则命中，也不可能成为替换目标；
 *    替换 SQL 只写 bookUrl/tocUrl 与章节 url 字段，从不写 name/author/type，
 *    因此「书源书籍与本地书籍同名」在本特性里**结构性不可能构成冲突**，本地书天然受保护。
 *    分类器保留 [ConflictKind.TARGET_IS_LOCAL] 作为防御性兜底（万一出现绝不自动处理）。
 * 3. 章节 `(url, bookUrl)` 冲突只会发生在**同一本书内部**（跨书 bookUrl 不同不撞键）：
 *    同一章存在新旧域名两行（部分迁移状态）。域名迁移的目的地是新域名，
 *    因此保留新域名行（自身 URL/标题等字段不动，不用旧记录覆盖），
 *    删除旧域名重复行；删除留下的 `index` 空洞由执行器「抬升-删除-紧凑重排」闭合，
 *    幸存章节相对顺序不变。
 */
object DomainReplaceConflicts {

    // ------------------------------------------------------------------ 类型

    /** 冲突类别（决定建议处理方式与可用操作） */
    enum class ConflictKind {
        /**
         * A1：已确认是同一本书的新旧书源记录——同一 book source + 书名一致 +
         * （章节路径重叠达标 或 bookUrl 路径相同）。
         * 建议：保留新域名记录为主，旧记录在用户确认后按规则清理。
         */
        SAME_BOOK_CONFIRMED,

        /**
         * 同书源、书名一致，但缺少章节/路径证据（目录未加载、结构变化）。
         * 交给用户查看确认，不提供自动清理入口。
         */
        SAME_BOOK_UNCONFIRMED,

        /** C：书名一致但来自不同书源，不能自动判定为同一本书 */
        SAME_NAME_DIFF_SOURCE,

        /** C：书名不同（或书名作者均不同），视为不同书 */
        DIFF_BOOK,

        /** B/D：目标是本地书籍（防御性兜底；正常数据下不可能出现），绝不自动处理 */
        TARGET_IS_LOCAL,

        /** D：同批替换里两本书要搬到同一个目标地址，至少有一本要用户裁决 */
        BATCH_TARGET_COLLISION,
    }

    /** 冲突项处理状态 */
    enum class ConflictStatus { PENDING, RESOLVED, SKIPPED, FAILED }

    /** 冲突清单里一行的最小书籍信息（稳定 ID = bookUrl，即 books 表主键） */
    data class ConflictBook(
        val bookUrl: String,
        val name: String,
        val author: String,
        val origin: String,
        val isLocal: Boolean,
        val chapterCount: Int,
        val chapterPaths: Set<String>,
        val hasProgress: Boolean,
    )

    /** 一条冲突：旧记录 + 目标记录 + 分类 + 状态 */
    data class ConflictItem(
        val oldBook: ConflictBook,
        val targetBook: ConflictBook?,
        val kind: ConflictKind,
        val status: ConflictStatus = ConflictStatus.PENDING,
        /** 处理说明（解决/跳过/失败原因），列表展示用 */
        val note: String? = null,
    ) {
        val oldBookUrl: String get() = oldBook.bookUrl
        val targetBookUrl: String? get() = targetBook?.bookUrl

        /** A1 且用户已确认的记录：提供「保留新记录并清理旧记录」入口 */
        val canCleanupOld: Boolean
            get() = kind == ConflictKind.SAME_BOOK_CONFIRMED &&
                    status == ConflictStatus.PENDING &&
                    targetBook != null
    }

    // ------------------------------------------------------------------ 路径与证据

    /**
     * 取 url 的 path?query#fragment 部分（scheme://authority 之后）；
     * 不是 http(s) 网址返回 null。域名迁移通常保持路径不变，
     * 因此路径是跨域名比较章节的稳定标识。
     */
    fun pathOf(url: String): String? {
        if (url.isEmpty()) return null
        val (body, _) = UrlDomainReplacement.splitUrlOption(url)
        val parts = UrlDomainReplacement.parseHttpUrl(body) ?: return null
        return parts.suffix.ifEmpty { "/" }
    }

    /** 书名一致性：去首尾空白、忽略大小写（宽严适度：完全不同写法不视为一致） */
    fun nameMatches(a: String, b: String): Boolean {
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    /** 证据一：bookUrl 去掉 scheme://authority 后的路径相同 */
    fun bookUrlPathMatches(oldBookUrl: String, targetBookUrl: String): Boolean {
        val a = pathOf(oldBookUrl) ?: return false
        val b = pathOf(targetBookUrl) ?: return false
        return a == b
    }

    /**
     * 证据二：章节路径重叠率。两边都有章节时要求
     * `共同路径数 ≥ 1 且 ≥ max(旧, 新) 的一半`；
     * 结构未变的域名迁移会远超这个阈值，而只抢到少量同名路径的
     * 不同书（或刚开书的新记录）达不到，宁可交给用户判断。
     */
    fun chapterEvidenceEnough(oldPaths: Set<String>, targetPaths: Set<String>): Boolean {
        if (oldPaths.isEmpty() || targetPaths.isEmpty()) return false
        val common = oldPaths.intersect(targetPaths).size
        if (common < 1) return false
        return common * 2 >= maxOf(oldPaths.size, targetPaths.size)
    }

    /**
     * 分类。`target == null` 表示目标地址未被占用（此时不该调用本函数）；
     * `batchCollides` 表示同批还有另一本书也要搬到同一目标地址。
     */
    fun classify(
        old: ConflictBook,
        target: ConflictBook,
        batchCollides: Boolean,
    ): ConflictKind {
        if (target.isLocal) return ConflictKind.TARGET_IS_LOCAL
        if (old.isLocal) return ConflictKind.TARGET_IS_LOCAL
        if (batchCollides) return ConflictKind.BATCH_TARGET_COLLISION
        val nameSame = nameMatches(old.name, target.name)
        if (!nameSame) return ConflictKind.DIFF_BOOK
        // 双方作者都有值且不同：同站同名的两本不同书（同名小说很常见），
        // 就算有章节路径重叠也不能自动判定为同一本——证据可能只是通用路径的巧合
        val authorComparable = old.author.isNotBlank() && target.author.isNotBlank()
        if (authorComparable && !nameMatches(old.author, target.author)) {
            return ConflictKind.SAME_NAME_DIFF_SOURCE
        }
        if (old.origin != target.origin) return ConflictKind.SAME_NAME_DIFF_SOURCE
        val chapterEvidence =
            chapterEvidenceEnough(old.chapterPaths, target.chapterPaths)
        val urlEvidence = bookUrlPathMatches(old.bookUrl, target.bookUrl)
        return if (chapterEvidence || urlEvidence) {
            ConflictKind.SAME_BOOK_CONFIRMED
        } else {
            ConflictKind.SAME_BOOK_UNCONFIRMED
        }
    }

    // ------------------------------------------------------------------ 进度映射（A1 清理用）

    /**
     * 按章节路径把旧记录的阅读位置映射到新记录目录。
     *
     * 旧记录当前章的路径，在新目录里找同路径章节（域名迁移路径不变的确定性来源）。
     * 返回新目录中的下标；找不到或旧记录没有进度返回 null。
     */
    fun mapProgressByPath(
        oldDurChapterIndex: Int,
        oldChapters: List<String>,
        targetPaths: List<String>,
    ): Int? {
        if (oldDurChapterIndex < 0 || oldDurChapterIndex >= oldChapters.size) return null
        val path = pathOf(oldChapters[oldDurChapterIndex]) ?: return null
        val idx = targetPaths.indexOf(path)
        return if (idx >= 0) idx else null
    }

    // ------------------------------------------------------------------ 章节去重后的进度修正

    /**
     * 章节 `(url, bookUrl)` 冲突的自动处理：删除旧域名重复行后再做前缀替换。
     * 删除行会让「按位置计」的阅读进度错位，这里做纯函数修正。
     *
     * @param durChapterIndex 修正前的阅读位置（在 [ordered] 里按下标定位）
     * @param ordered 该书章节按 `index` 列升序的 url 列表（修正前）
     * @param deletedUrls 将被删除的旧域名重复行 url 集合
     * @param pathOfSame 提取章节路径的函数（与 [pathOf] 一致，参数化便于测试）
     * @return (修正后的位置, 该位置对应章节的 url)——调用方可用 url 取新标题；
     *         入参无效或没有幸存章节时返回 null（调用方保持原值并报告）
     */
    fun adjustProgressAfterDedupe(
        durChapterIndex: Int,
        ordered: List<String>,
        deletedUrls: Set<String>,
        pathOfSame: (String) -> String? = ::pathOf,
    ): Pair<Int, String>? {
        if (durChapterIndex < 0 || durChapterIndex >= ordered.size) return null
        if (deletedUrls.isEmpty()) return durChapterIndex to ordered[durChapterIndex]
        // 删除行的保留副本与它同路径（同章节的新域名行）
        val survivorPaths = survivors(ordered, deletedUrls).mapNotNull { pathOfSame(it) }.toSet()
        if (survivorPaths.isEmpty()) return null
        val targetUrl = ordered[durChapterIndex]
        val targetPath = pathOfSame(targetUrl) ?: return null
        survivors(ordered, deletedUrls).forEachIndexed { index, url ->
            if (pathOfSame(url) == targetPath) return index to url
        }
        return null
    }

    private fun survivors(ordered: List<String>, deletedUrls: Set<String>): List<String> =
        ordered.filter { it !in deletedUrls }
}
