package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.MD5Utils
import java.io.File

/**
 * 旧版章节正文缓存迁移器（用户主动触发，先扫描预览、确认后执行）。
 *
 * 旧格式文件名：`%05d-index-titleMD5.nb`（第一阶段前的写入格式）。
 * 新格式文件名：标题唯一 `titleMD5.nb`；标题完全重复 `index-titleMD5.nb`。
 *
 * 安全原则（优先于迁移数量）：
 * - 归类必须同时依据当前真实章节目录与磁盘状态，仅凭文件名相似不迁移；
 * - 同一 titleMD5 存在多个有效旧文件时视为竞争候选，无法证明归属，全部保留；
 * - 标题完全重复的章节：旧 index 与当前精确相等才算「已是新键」（无需操作），
 *   否则保留——不允许把不同重复标题章节的正文合并到同一个新文件；
 * - 目标新文件已存在时不覆盖、不删除旧文件；
 * - 执行采用同目录重命名（原子），重命名前重查源与目标状态，重命名后校验长度；
 *   任何失败保留原文件，单个文件失败不影响其他独立文件；
 * - 不迁移、不删除「nr」等非默认后缀标记文件（第一阶段已双键兼容，无需迁移）；
 * - 不扫描、不修改已删除书籍的缓存目录。
 */
object ChapterCacheMigrator {

    /** 旧格式正文缓存文件名：至少 5 位 index（章节超过 99999 时为 6 位）+ 16 位标题 MD5 */
    private val legacyRegex = Regex("^(\\d{5,})-([0-9a-f]{16})\\.nb$")

    /** 单个旧文件在本次扫描中的处置分类（互斥，统计口径不重复计数） */
    enum class Action {
        /** A：标题唯一、无竞争、旧 index 与当前精确匹配、目标不存在 → 可安全重命名 */
        RENAME_SAFE,

        /** B：标题唯一、无竞争（同 MD5 有效旧文件仅此一个）、旧 index 与当前不等 → 默认保留，用户显式确认后才迁移 */
        RENAME_UNIQUE_MOVED,

        /** D：目标新文件已存在 → 不覆盖，旧文件保留 */
        KEEP_TARGET_EXISTS,

        /** C：标题完全重复且旧 index 不等于当前章节 index → 保留，防串章 */
        KEEP_DUPLICATE,

        /** E：同 titleMD5 的有效旧文件不止一个且标题唯一 → 竞争候选，无法证明归属，保留 */
        KEEP_AMBIGUOUS,

        /** E：titleMD5 对不上当前目录任何章节标题（章节被删/标题已改）→ 保留 */
        KEEP_ORPHAN,

        /** 已是新键：标题完全重复且旧 index 与当前精确相等，文件名即新键，无需操作 */
        KEEP_ALREADY_NEW,

        /** E：空文件、目录、不可读等异常 → 跳过并报告 */
        SKIP_INVALID
    }

    data class LegacyItem(
        val fileName: String,
        val oldIndex: Int,
        val titleMd5: String,
        val length: Long,
        val action: Action,
        val targetName: String?
    )

    data class BookPlan(
        val bookUrl: String,
        val bookName: String,
        val bookDirPath: String,
        val items: List<LegacyItem>,
        val nrFlagFiles: Int
    ) {
        val total: Int get() = items.size
        val safe: Int get() = items.count { it.action == Action.RENAME_SAFE }
        val uniqueMoved: Int get() = items.count { it.action == Action.RENAME_UNIQUE_MOVED }
        val targetExists: Int get() = items.count { it.action == Action.KEEP_TARGET_EXISTS }
        val duplicateKept: Int get() = items.count { it.action == Action.KEEP_DUPLICATE }
        val ambiguous: Int get() = items.count { it.action == Action.KEEP_AMBIGUOUS }
        val orphan: Int get() = items.count { it.action == Action.KEEP_ORPHAN }
        val alreadyNew: Int get() = items.count { it.action == Action.KEEP_ALREADY_NEW }
        val invalid: Int get() = items.count { it.action == Action.SKIP_INVALID }

        /** 需要人工判断或无法确认的数量（B + 重复保留 + 竞争 + 孤儿） */
        val needConfirm: Int get() = uniqueMoved + duplicateKept + ambiguous + orphan
    }

    /** 全库扫描汇总 */
    data class ScanSummary(
        val booksChecked: Int,
        val booksWithCacheDir: Int,
        val deletedBookDirs: Int,
        val nrFlagFiles: Int,
        val bookPlans: List<BookPlan>
    ) {
        val total: Int get() = bookPlans.sumOf { it.total }
        val safe: Int get() = bookPlans.sumOf { it.safe }
        val uniqueMoved: Int get() = bookPlans.sumOf { it.uniqueMoved }
        val targetExists: Int get() = bookPlans.sumOf { it.targetExists }
        val duplicateKept: Int get() = bookPlans.sumOf { it.duplicateKept }
        val ambiguous: Int get() = bookPlans.sumOf { it.ambiguous }
        val orphan: Int get() = bookPlans.sumOf { it.orphan }
        val alreadyNew: Int get() = bookPlans.sumOf { it.alreadyNew }
        val invalid: Int get() = bookPlans.sumOf { it.invalid }
        val needConfirm: Int get() = bookPlans.sumOf { it.needConfirm }
    }

    /** 执行结果 */
    data class ExecuteStats(
        val renamed: Int,
        val skipped: Int,
        val failed: Int,
        val total: Int
    )

    /**
     * 扫描预览的统计文本（预览对话框显示与「复制扫描结果」同源，保证复制内容与所见一致）。
     * 仅做结果呈现，不参与扫描与迁移逻辑。
     */
    fun buildScanReportText(summary: ScanSummary): String {
        return buildString {
            append("扫描书籍 ${summary.booksChecked} 本（有缓存目录 ${summary.booksWithCacheDir} 本）\n")
            append("发现旧格式正文缓存 ${summary.total} 个：\n")
            append("· 可安全迁移（标题唯一且序号匹配）：${summary.safe}\n")
            append("· 唯一候选但序号不匹配（默认不迁移）：${summary.uniqueMoved}\n")
            append("· 目标新缓存已存在（不覆盖）：${summary.targetExists}\n")
            append("· 重复标题章节（保留防串章）：${summary.duplicateKept}\n")
            append("· 已是新键无需处理：${summary.alreadyNew}\n")
            append("· 无法确认归属（竞争候选/孤儿）：${summary.ambiguous + summary.orphan}\n")
            append("· 空文件或异常：${summary.invalid}\n")
            if (summary.nrFlagFiles > 0) {
                append("· 「去重标题」标记文件 ${summary.nrFlagFiles} 个：已双键兼容，无需迁移\n")
            }
            if (summary.deletedBookDirs > 0) {
                append("· 已删除书籍的缓存目录 ${summary.deletedBookDirs} 个：不处理\n")
            }
        }
    }

    /**
     * 扫描单本书：只读磁盘与章节列表，不修改任何文件。
     * 会以传入章节列表刷新 [ChapterTitleRegistry]，保证目标名与 BookHelp 写入侧一致。
     */
    fun plan(bookUrl: String, bookName: String, bookDir: File, chapters: List<BookChapter>): BookPlan {
        ChapterTitleRegistry.update(bookUrl, chapters.map { it.title })
        // 卷名章节从不写正文缓存，不参与归属匹配
        val contentChapters = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
        val titles = contentChapters.map { it.title }
        val dupTitles = titles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        // 当前章节按 titleMD5 分组（与旧版写入时的 md5Encode16(title) 同源）
        val chaptersByMd5 = contentChapters.groupBy { MD5Utils.md5Encode16(it.title) }
        val fileEntries = bookDir.listFiles()
            ?.mapNotNull { file ->
                legacyRegex.matchEntire(file.name)?.let { m ->
                    Triple(file, m.groupValues[1].toInt(), m.groupValues[2])
                }
            }
            ?: emptyList()
        // nr 等非默认后缀标记文件单独计数，不参与迁移
        val nrFlagFiles = bookDir.listFiles()?.count { it.name.endsWith(".nr") } ?: 0

        val validEntries = fileEntries.filter { (file, _, _) -> isUsableFile(file) }
        val validCountByMd5 = validEntries.groupingBy { it.third }.eachCount()

        val items = fileEntries.map { (file, oldIndex, fileMd5) ->
            if (!isUsableFile(file)) {
                LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.SKIP_INVALID, null)
            } else {
                classify(file, oldIndex, fileMd5, bookDir, chaptersByMd5, dupTitles, validCountByMd5)
            }
        }
        return BookPlan(bookUrl, bookName, bookDir.absolutePath, items, nrFlagFiles)
    }

    private fun isUsableFile(file: File): Boolean {
        return file.isFile && file.length() > 0 && file.canRead()
    }

    private fun classify(
        file: File,
        oldIndex: Int,
        fileMd5: String,
        bookDir: File,
        chaptersByMd5: Map<String, List<BookChapter>>,
        dupTitles: Set<String>,
        validCountByMd5: Map<String, Int>
    ): LegacyItem {
        val candidates = chaptersByMd5[fileMd5].orEmpty()
        if (candidates.isEmpty()) {
            return LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.KEEP_ORPHAN, null)
        }
        val isDupTitle = dupTitles.contains(candidates.first().title)
        if (isDupTitle) {
            // 标题完全重复：旧 index 与当前某章节精确相等 = 文件已在（等同）新键位；否则无法区分 twin，保留
            val matched = candidates.firstOrNull { it.index == oldIndex }
            return if (matched != null) {
                LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.KEEP_ALREADY_NEW, null)
            } else {
                LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.KEEP_DUPLICATE, null)
            }
        }
        // 标题唯一：存在其他同 MD5 有效旧文件即为竞争候选（可能来自重复标题历史或多次重排残留），无法证明归属
        if ((validCountByMd5[fileMd5] ?: 0) > 1) {
            return LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.KEEP_AMBIGUOUS, null)
        }
        val chapter = candidates.first()
        val targetName = chapter.getFileName()
        if (File(bookDir, targetName).exists()) {
            return LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.KEEP_TARGET_EXISTS, targetName)
        }
        return if (oldIndex == chapter.index) {
            LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.RENAME_SAFE, targetName)
        } else {
            LegacyItem(file.name, oldIndex, fileMd5, file.length(), Action.RENAME_UNIQUE_MOVED, targetName)
        }
    }

    /**
     * 执行单本书迁移：逐文件处理，不把正文读入内存（同目录重命名）。
     *
     * 每个文件处理前重新检查源与目标状态；目标存在绝不覆盖；重命名后校验目标长度；
     * 任何失败保留原文件并继续下一个独立文件；发生文件系统级异常时中止剩余文件，
     * 避免在异常状态下继续批量操作。
     *
     * @param includeMoved 是否同时迁移 B 类（标题唯一但旧 index 与当前不等的唯一候选）
     * @param renameOp 重命名操作，默认同目录 renameTo；测试注入失败用
     */
    fun execute(
        bookPlan: BookPlan,
        includeMoved: Boolean,
        renameOp: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) },
        onProgress: (done: Int, stats: ExecuteStats) -> Unit = { _, _ -> }
    ): ExecuteStats {
        val bookDir = File(bookPlan.bookDirPath)
        if (!bookDir.isDirectory) {
            // 目录已消失（用户中途清缓存等）：无可执行项，全部计为跳过
            val stats = ExecuteStats(0, 0, 0, 0)
            onProgress(0, stats)
            return stats
        }
        val targets = bookPlan.items.filter {
            it.action == Action.RENAME_SAFE || (includeMoved && it.action == Action.RENAME_UNIQUE_MOVED)
        }
        var renamed = 0
        var skipped = 0
        var failed = 0
        var aborted = false
        targets.forEachIndexed { idx, item ->
            if (aborted) {
                skipped++
                onProgress(idx + 1, ExecuteStats(renamed, skipped, failed, targets.size))
                return@forEachIndexed
            }
            val result = try {
                renameOne(bookDir, item, renameOp)
            } catch (e: Exception) {
                AppLog.put("迁移旧缓存异常 ${bookPlan.bookName} ${item.fileName}", e)
                // 文件系统级异常：中止剩余，避免在异常状态下继续批量处理
                aborted = true
                -1
            }
            when {
                result > 0 -> renamed++
                result == 0 -> skipped++
                else -> failed++
            }
            onProgress(idx + 1, ExecuteStats(renamed, skipped, failed, targets.size))
        }
        return ExecuteStats(renamed, skipped, failed, targets.size)
    }

    /** 返回 1=成功，0=跳过（状态已变化），-1=失败（原文件保留） */
    private fun renameOne(bookDir: File, item: LegacyItem, renameOp: (File, File) -> Boolean): Int {
        val target = item.targetName ?: return 0
        val src = File(bookDir, item.fileName)
        val dst = File(bookDir, target)
        // 处理前重查源状态：被并发删除/修改/已非旧格式时跳过，不猜测
        if (!src.isFile || src.length() != item.length || !legacyRegex.matches(src.name)) {
            return 0
        }
        // 目标已存在绝不覆盖（renameTo 在 Linux 上会静默覆盖，必须前置检查）
        if (dst.exists()) {
            return 0
        }
        if (!renameOp(src, dst)) {
            return -1
        }
        // 重命名后校验：目标存在且长度与源一致才算成功
        if (!dst.isFile || dst.length() != item.length) {
            return -1
        }
        return 1
    }
}
