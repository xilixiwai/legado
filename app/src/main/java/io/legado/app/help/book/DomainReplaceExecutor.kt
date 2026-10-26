package io.legado.app.help.book

import io.legado.app.data.dao.DomainGroupRow

/**
 * 章节表某个字段的一组待替换 authority。
 *
 * [prefix] = `scheme://authority`，与 SQL 的命中条件完全一致；
 * [newPrefix] = `scheme://newAuthority`，SQL 直接用它替换前缀，不在 SQL 里做字符串推断。
 */
class ChapterFieldChange(
    /** 分组查询返回的 scheme，已含 `://`，例如 `https://` */
    val scheme: String,
    val authority: String,
    val newAuthority: String,
    val count: Int,
) {
    val prefix: String
        get() = scheme + authority

    val newPrefix: String
        get() = scheme + newAuthority
}

/** 书籍两处地址的更新（`bookUrl` 是主键，与 `tocUrl` 必须同一条语句更新） */
data class BookUrlUpdate(
    val oldBookUrl: String,
    val newBookUrl: String,
    val newTocUrl: String,
)

/** 书籍主键迁移，用于同步 `chapters.bookUrl` */
data class BookUrlMove(
    val oldBookUrl: String,
    val newBookUrl: String,
)

/**
 * 一次批量替换的写入计划。
 *
 * 只包含字符串，不引用 Room / Book 实体，因此可以在 JVM 单元测试里用真实 SQLite 跑完整流程。
 */
data class DomainReplacePlan(
    val urlChanges: List<ChapterFieldChange> = emptyList(),
    val baseUrlChanges: List<ChapterFieldChange> = emptyList(),
    val resourceUrlChanges: List<ChapterFieldChange> = emptyList(),
    val bookUpdates: List<BookUrlUpdate> = emptyList(),
    val bookMoves: List<BookUrlMove> = emptyList(),
) {
    val isEmpty: Boolean
        get() = urlChanges.isEmpty() && baseUrlChanges.isEmpty() &&
                resourceUrlChanges.isEmpty() && bookUpdates.isEmpty()
}

    /** 下发到存储层的单条写操作 */
    sealed class DomainReplaceStatement {

        /** 章节表某一列的 authority 前缀替换（三列共用同一套 SQL 形态） */
        sealed class ChapterField : DomainReplaceStatement() {
            abstract val prefix: String
            abstract val newPrefix: String
        }

        data class ChapterUrl(override val prefix: String, override val newPrefix: String) :
            ChapterField()

        data class ChapterBaseUrl(override val prefix: String, override val newPrefix: String) :
            ChapterField()

        data class ChapterResourceUrl(override val prefix: String, override val newPrefix: String) :
            ChapterField()

        /**
         * 章节主键冲突去重：删除改写后会与同书已有新域名行撞 `(url, bookUrl)` 键的
         * 旧域名重复行，**保留新域名行**（迁移目的地，身份/URL/元数据不动）。
         * 自动删除的证据 = 同一本书 + 用户声明的域名映射 + 规范化路径相同；
         * 必须夹在同前缀的 [ChapterDedupLift] / [ChapterDedupSettle] 之间执行；
         * 影响行数单独计数并报告。
         */
        data class ChapterUrlDedupe(val prefix: String, val newPrefix: String) :
            DomainReplaceStatement()

        /**
         * 章节去重配套第一步：把存在将删除重复行的书籍的全部章节 `index` 抬升到
         * [offset] 起的高位空区，为删除后的紧凑重排腾出低位（详见 SQL KDoc）。
         */
        data class ChapterDedupLift(
            val prefix: String,
            val newPrefix: String,
            val offset: Int = DomainReplaceExecutor.INDEX_COMPACT_OFFSET,
        ) : DomainReplaceStatement()

        /**
         * 章节去重配套第二步：仍停留在高位空区的章节行按幸存相对顺序
         * 紧凑重排为 `0..K-1`，消除删除旧域名行留下的 `index` 空洞。
         */
        data class ChapterDedupSettle(val offset: Int = DomainReplaceExecutor.INDEX_COMPACT_OFFSET) :
            DomainReplaceStatement()

        data class BookUrls(val oldBookUrl: String, val newBookUrl: String, val newTocUrl: String) :
            DomainReplaceStatement()

        data class ChapterBookUrl(val oldBookUrl: String, val newBookUrl: String) :
            DomainReplaceStatement()
    }

    /** 各字段实际影响行数，全部来自 SQL 的返回值 */
    data class DomainReplaceCounts(
        val chapterUrl: Int = 0,
        val chapterBaseUrl: Int = 0,
        val chapterResourceUrl: Int = 0,
        val books: Int = 0,
        val chapterBookUrl: Int = 0,
        /** 因章节主键冲突被删除的旧域名重复章节数（不算进 chapterUrl 的改写行数） */
        val chapterUrlDuplicates: Int = 0,
    )

/**
 * 批量替换的**写入顺序**与**外键处理**。
 *
 * 抽成独立对象的原因：JVM 单元测试跑不了 Room，但事务顺序、`defer_foreign_keys` 的位置、
 * 「先章节后书籍」这些细节恰恰是最容易出错的地方。放在这里，生产代码和真实 SQLite 测试
 * 调用的是**同一段顺序逻辑**，测试才有意义。
 *
 * 顺序：
 * 1. `chapters.url` / `baseUrl` / `resourceUrl` —— 不参与外键，先改最安全；
 * 2. `PRAGMA defer_foreign_keys = ON` —— `books.bookUrl` 是 `chapters.bookUrl` 的外键目标，
 *    改 books 的瞬间 chapters 还指向旧地址。这只是把外键检查从语句级推迟到事务提交时，
 *    **不是关闭约束**：提交时外键仍会校验；
 * 3. `books.bookUrl + tocUrl` —— 主键与目录地址同一条语句，不产生中间态；
 * 4. `chapters.bookUrl` —— 跟上书籍主键。
 */
object DomainReplaceExecutor {

    /** 推迟外键检查到事务提交时 */
    const val DEFER_FOREIGN_KEYS = "PRAGMA defer_foreign_keys = ON"

    /**
     * 章节去重紧凑重排的高位空区起点。任何一本书的章节数都远小于该值，
     * 抬升后的目标区间必然为空，重排的每个目标位也都为空，
     * 因此两步 UPDATE 都不依赖 SQLite 的行更新顺序。
     */
    const val INDEX_COMPACT_OFFSET = 1_000_000_000

    /**
     * 把 SQL 分组结果（每行一个 authority）转成待更新的分组。
     *
     * 章节表可能有几十万行，但 authority 种类通常只有个位数，
     * 因此真正下发的 UPDATE 只有几条，匹配与写入全部由 SQLite 完成。
     *
     * 返回的分组就是「预览数量」的口径：预览与实际更新都走这个函数，
     * 不会出现「预览按一种规则算、写入按另一种规则算」。
     */
    fun changes(
        groups: List<DomainGroupRow>,
        rule: UrlDomainReplacement.DomainRule,
    ): List<ChapterFieldChange> {
        return groups.mapNotNull { group ->
            val newAuthority = UrlDomainReplacement.newAuthorityFor(
                authority = group.authority,
                rule = rule,
            ) ?: return@mapNotNull null
            ChapterFieldChange(
                scheme = group.scheme,
                authority = group.authority,
                newAuthority = newAuthority,
                count = group.cnt,
            )
        }
    }

    /** 按固定顺序展开全部写操作（与 [run] 的发射顺序一致），便于测试直接断言顺序 */
    fun statements(plan: DomainReplacePlan): List<DomainReplaceStatement> {
        val list = ArrayList<DomainReplaceStatement>()
        plan.urlChanges.forEach {
            list.add(DomainReplaceStatement.ChapterDedupLift(it.prefix, it.newPrefix))
            list.add(DomainReplaceStatement.ChapterUrlDedupe(it.prefix, it.newPrefix))
            list.add(DomainReplaceStatement.ChapterDedupSettle())
            list.add(DomainReplaceStatement.ChapterUrl(it.prefix, it.newPrefix))
        }
        plan.baseUrlChanges.forEach {
            list.add(DomainReplaceStatement.ChapterBaseUrl(it.prefix, it.newPrefix))
        }
        plan.resourceUrlChanges.forEach {
            list.add(DomainReplaceStatement.ChapterResourceUrl(it.prefix, it.newPrefix))
        }
        plan.bookUpdates.forEach {
            list.add(DomainReplaceStatement.BookUrls(it.oldBookUrl, it.newBookUrl, it.newTocUrl))
        }
        plan.bookMoves.forEach {
            list.add(DomainReplaceStatement.ChapterBookUrl(it.oldBookUrl, it.newBookUrl))
        }
        return list
    }

    /**
     * 在调用方已经开启的事务里执行全部写操作。
     *
     * @param execSql 执行无返回值的语句（这里只用于 `PRAGMA defer_foreign_keys`）
     * @param apply 执行一条写操作并返回影响行数
     */
    fun run(
        plan: DomainReplacePlan,
        execSql: (String) -> Unit,
        apply: (DomainReplaceStatement) -> Int,
    ): DomainReplaceCounts {
        var chapterUrl = 0
        var chapterBaseUrl = 0
        var chapterResourceUrl = 0
        var books = 0
        var chapterBookUrl = 0
        var chapterUrlDuplicates = 0

        // 1. 章节地址：不参与外键，先改。每个前缀组内：抬升 index → 删除撞键的
        //    旧域名重复行（保留新域名行）→ 紧凑重排 index → 改写 url
        plan.urlChanges.forEach {
            apply(DomainReplaceStatement.ChapterDedupLift(it.prefix, it.newPrefix))
            chapterUrlDuplicates += apply(
                DomainReplaceStatement.ChapterUrlDedupe(it.prefix, it.newPrefix)
            )
            apply(DomainReplaceStatement.ChapterDedupSettle())
            chapterUrl += apply(DomainReplaceStatement.ChapterUrl(it.prefix, it.newPrefix))
        }
        plan.baseUrlChanges.forEach {
            chapterBaseUrl += apply(DomainReplaceStatement.ChapterBaseUrl(it.prefix, it.newPrefix))
        }
        plan.resourceUrlChanges.forEach {
            chapterResourceUrl +=
                apply(DomainReplaceStatement.ChapterResourceUrl(it.prefix, it.newPrefix))
        }

        // 2. 改 books 主键前把外键检查推迟到提交时
        if (plan.bookUpdates.isNotEmpty()) {
            execSql(DEFER_FOREIGN_KEYS)
        }

        // 3. 书籍主键与目录地址
        plan.bookUpdates.forEach {
            books += apply(
                DomainReplaceStatement.BookUrls(it.oldBookUrl, it.newBookUrl, it.newTocUrl)
            )
        }

        // 4. 章节归属跟上书籍主键
        plan.bookMoves.forEach {
            chapterBookUrl +=
                apply(DomainReplaceStatement.ChapterBookUrl(it.oldBookUrl, it.newBookUrl))
        }

        return DomainReplaceCounts(
            chapterUrl = chapterUrl,
            chapterBaseUrl = chapterBaseUrl,
            chapterResourceUrl = chapterResourceUrl,
            books = books,
            chapterBookUrl = chapterBookUrl,
            chapterUrlDuplicates = chapterUrlDuplicates,
        )
    }
}
