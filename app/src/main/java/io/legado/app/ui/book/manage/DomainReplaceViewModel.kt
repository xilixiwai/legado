package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.dao.DomainGroupRow
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookUrlMove
import io.legado.app.help.book.BookUrlUpdate
import io.legado.app.help.book.ChapterFieldChange
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.DomainReplaceAudit
import io.legado.app.help.book.DomainReplaceConflicts
import io.legado.app.help.book.DomainReplaceConflicts.ConflictItem
import io.legado.app.help.book.DomainReplaceConflicts.ConflictKind
import io.legado.app.help.book.DomainReplaceConflicts.ConflictStatus
import io.legado.app.help.book.DomainReplaceCounts
import io.legado.app.help.book.DomainReplaceExecutor
import io.legado.app.help.book.DomainReplacePlan
import io.legado.app.help.book.DomainReplacePlannedCounts
import io.legado.app.help.book.DomainReplaceStatement
import io.legado.app.help.book.UrlDomainReplacement
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** 「批量替换域名」的命中统计，按数据库字段拆分。预览与执行结果都用它汇报。 */
data class DomainReplaceHits(
    val bookUrl: Int = 0,
    val tocUrl: Int = 0,
    val chapterBookUrl: Int = 0,
    val chapterUrl: Int = 0,
    val chapterBaseUrl: Int = 0,
    val chapterResourceUrl: Int = 0,
) {
    val total: Int
        get() = bookUrl + tocUrl + chapterBookUrl + chapterUrl + chapterBaseUrl + chapterResourceUrl

    val hasAny: Boolean
        get() = total > 0
}

/** 会被改写的 authority（主机名，可能含端口），用于预览时让用户看清影响范围 */
data class DomainAuthorityChange(
    /** 字段名，例如 "chapters.url" */
    val field: String,
    val oldAuthority: String,
    val newAuthority: String,
    val count: Int,
)

/** 「检查」结果：要么给出可执行的预览（含冲突分区），要么给出不执行的原因 */
sealed class DomainReplaceCheckResult {
    data class Blocked(val reason: String) : DomainReplaceCheckResult()
    data class Ready(val preview: DomainReplacePreview) : DomainReplaceCheckResult()
}

/** 单本书籍的替换计划；[newBookUrl] 等于 [Book.bookUrl] 表示该字段不需要改 */
internal class BookPlan(
    val book: Book,
    val newBookUrl: String,
    val newTocUrl: String,
) {
    val oldBookUrl: String
        get() = book.bookUrl

    val bookUrlChanged: Boolean
        get() = newBookUrl != book.bookUrl

    val tocUrlChanged: Boolean
        get() = newTocUrl != book.tocUrl

    val changed: Boolean
        get() = bookUrlChanged || tocUrlChanged
}

/** 冲突处理动作的结果（重新检查 / 清理旧记录） */
internal class ConflictActionResult(
    val item: ConflictItem,
    val status: ConflictStatus,
    val note: String?,
)

/**
 * 「批量替换域名」的 ViewModel：独立于任何列表选择状态，作用范围由旧域名规则与数据库决定。
 *
 * 与旧「批量换源域名」的本质区别：不再按书籍、书源逐本迁移，而是把「旧域名 → 新域名」
 * 当作一次全库维护操作。每个字段独立匹配，因此「bookUrl 已是新域名、tocUrl 仍是旧域名」
 * 这类部分完成的状态能被继续修复，而不会整本书被跳过。
 *
 * 冲突工作流：预览分区「可直接替换 / 待处理冲突」；执行只写无冲突部分，单条冲突不阻断
 * 其他记录；冲突项带稳定 ID（bookUrl）进入冲突清单，支持查看书籍、重新检查、
 * 已确认同书的旧记录清理与跳过，处理状态保存在 ViewModel 与 [SavedStateHandle] 中。
 *
 * [checkDomainReplace] / [executeDomainReplace] 必须在主线程调用（执行前要同步建立 UI 状态）。
 */
class DomainReplaceViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : BaseViewModel(application) {

    /** 执行中状态：必须同步建立，保证等待框在任何执行速度下都会出现 */
    val domainReplaceBusy = MutableLiveData<Boolean>()

    /** 执行阶段文字（准备 / 更新数据库 / 完成事务并核对），由 WaitDialog 展示 */
    val domainReplaceProcessLiveData = MutableLiveData<String>()

    /** 一次性事件：UI 消费后置空，避免旋转屏幕时重复弹窗 */
    val domainReplaceCheckLiveData = MutableLiveData<DomainReplaceCheckResult?>()
    val domainReplaceResultLiveData = MutableLiveData<DomainReplaceResult?>()

    /** 冲突清单（持续可操作，不是一次性事件）：预览与执行后都会更新 */
    val conflictItemsLiveData = MutableLiveData<List<ConflictItem>>()

    var domainReplaceCoroutine: Coroutine<*>? = null

    /**
     * 检查/执行/冲突处理的互斥标记：数据库事务无法安全中断，
     * 任务进行期间禁止再次提交，防止重复执行与「取消后无反馈」。
     */
    private val domainReplaceRunning = AtomicBoolean(false)

    /** 当前冲突清单的工作副本；清单弹窗与结果弹窗都以它为准 */
    private var conflictItems: List<ConflictItem> = emptyList()

    // ------------------------------------------------------------------ 任务上下文（返回恢复 / 进程重建）

    private fun saveTaskContext(oldInput: String, newInput: String) {
        savedState[KEY_OLD_INPUT] = oldInput
        savedState[KEY_NEW_INPUT] = newInput
    }

    /** 上次检查使用的旧域名输入（进程重建后预填输入框，避免重抄长正则） */
    val taskOldInput: String
        get() = savedState.get(KEY_OLD_INPUT) ?: ""

    /** 上次检查使用的新域名输入 */
    val taskNewInput: String
        get() = savedState.get(KEY_NEW_INPUT) ?: ""

    private fun persistStatuses() {
        val map = conflictItems.associate {
            it.oldBookUrl to "${it.status.name}|${it.note.orEmpty()}"
        }
        savedState[KEY_STATUSES] = GSON.toJson(map)
    }

    private fun restoreStatuses(items: List<ConflictItem>): List<ConflictItem> {
        val json = savedState.get<String>(KEY_STATUSES) ?: return items
        val map = runCatching {
            GSON.fromJson<Map<String, String>>(json, Map::class.java)
        }.getOrNull() ?: return items
        return items.map { item ->
            val saved = map[item.oldBookUrl] ?: return@map item
            val statusName = saved.substringBefore('|')
            val note = saved.substringAfter('|', "").ifEmpty { null }
            val status = runCatching { ConflictStatus.valueOf(statusName) }
                .getOrNull() ?: return@map item
            if (status == ConflictStatus.PENDING) item
            else item.copy(status = status, note = note)
        }
    }

    private fun publishConflictItems() {
        persistStatuses()
        conflictItemsLiveData.postValue(conflictItems)
    }

    private fun currentRule(): UrlDomainReplacement.DomainRule? {
        val oldInput = taskOldInput
        val newInput = taskNewInput
        if (oldInput.isBlank() || newInput.isBlank()) return null
        return when (val r = UrlDomainReplacement.normalize(oldInput, newInput)) {
            is UrlDomainReplacement.NormalizeResult.Failure -> null
            is UrlDomainReplacement.NormalizeResult.Success -> r.rule
        }
    }

    // ------------------------------------------------------------------ 检查

    /** 「检查」：只读扫描，不写库、不动缓存、不动阅读器 */
    fun checkDomainReplace(oldInput: String, newInput: String) {
        if (!domainReplaceRunning.compareAndSet(false, true)) {
            domainReplaceCheckLiveData.postValue(
                DomainReplaceCheckResult.Blocked(
                    context.getString(R.string.change_domain_task_running)
                )
            )
            return
        }
        domainReplaceCoroutine?.cancel()
        domainReplaceCoroutine = execute {
            buildDomainReplaceCheck(oldInput, newInput)
        }.onSuccess {
            domainReplaceCheckLiveData.postValue(it)
        }.onError {
            domainReplaceCheckLiveData.postValue(
                DomainReplaceCheckResult.Blocked(
                    it.localizedMessage ?: it.javaClass.simpleName
                )
            )
        }.onFinally {
            domainReplaceRunning.set(false)
        }
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 用户确认后执行；所有写入在一个事务内完成。只写无冲突部分，
     * 冲突项原样保留进冲突清单，单条冲突不再阻断其他记录。
     */
    fun executeDomainReplace(oldInput: String, newInput: String) {
        if (!domainReplaceRunning.compareAndSet(false, true)) {
            domainReplaceCheckLiveData.postValue(
                DomainReplaceCheckResult.Blocked(
                    context.getString(R.string.change_domain_task_running)
                )
            )
            return
        }
        domainReplaceBusy.value = true
        domainReplaceCoroutine?.cancel()
        domainReplaceCoroutine = execute {
            runDomainReplace(oldInput, newInput)
        }.onSuccess {
            domainReplaceResultLiveData.postValue(it)
        }.onError {
            if (it is CancellationException) return@onError
            AppLog.put("批量替换域名失败\n${it.localizedMessage}", it, true)
            domainReplaceResultLiveData.postValue(
                DomainReplaceResult(
                    rule = null,
                    abortReason = it.localizedMessage ?: it.javaClass.simpleName,
                )
            )
        }.onFinally {
            // onFinally 在成功/失败/取消三种结局都会执行，互斥标记必须在这里释放
            domainReplaceRunning.set(false)
            domainReplaceBusy.postValue(false)
        }
    }

    // ------------------------------------------------------------------ 计划与预览

    /**
     * 书籍表的替换计划。
     *
     * books 表只有书架规模（数百行），直接读出来用与执行阶段**完全相同**的纯函数判断，
     * 比在 SQL 里重复实现一遍主机名解析更不容易出现口径差异。
     */
    private fun planBooks(
        rule: UrlDomainReplacement.DomainRule,
        books: List<Book> = appDb.bookDao.all,
    ): List<BookPlan> {
        return books.mapNotNull { book ->
            val urls = UrlDomainReplacement.replaceBookUrls(
                bookUrl = book.bookUrl,
                tocUrl = book.tocUrl,
                rule = rule,
            ) ?: return@mapNotNull null
            // 某个字段已经是新域名时保留原值，另一个字段继续替换
            BookPlan(
                book = book,
                newBookUrl = urls.bookUrl ?: book.bookUrl,
                newTocUrl = urls.tocUrl ?: book.tocUrl,
            )
        }
    }

    /**
     * 把 SQL 分组结果（每行一个 authority）转成待更新的分组。
     *
     * 实现放在 [DomainReplaceExecutor.changes]，与真实 SQLite 回归测试共用同一段逻辑。
     */
    private fun chapterFieldChanges(
        groups: List<DomainGroupRow>,
        rule: UrlDomainReplacement.DomainRule,
    ): List<ChapterFieldChange> = DomainReplaceExecutor.changes(groups, rule)

    private fun buildDomainReplaceCheck(oldInput: String, newInput: String): DomainReplaceCheckResult {
        val rule = when (val result = UrlDomainReplacement.normalize(oldInput, newInput)) {
            is UrlDomainReplacement.NormalizeResult.Failure ->
                return DomainReplaceCheckResult.Blocked(invalidReasonText(result.reason))

            is UrlDomainReplacement.NormalizeResult.Success -> result.rule
        }
        saveTaskContext(oldInput, newInput)
        val allBooks = appDb.bookDao.all
        val bookPlans = planBooks(rule, allBooks)
        val urlGroups = appDb.bookChapterDao.domainGroupsOfChapterUrl()
        val baseUrlGroups = appDb.bookChapterDao.domainGroupsOfChapterBaseUrl()
        val resourceUrlGroups = appDb.bookChapterDao.domainGroupsOfChapterResourceUrl()
        val urlChanges = chapterFieldChanges(urlGroups, rule)
        val baseUrlChanges = chapterFieldChanges(baseUrlGroups, rule)
        val resourceUrlChanges = chapterFieldChanges(resourceUrlGroups, rule)
        val movingBooks = bookPlans.filter { it.bookUrlChanged }
        val hits = DomainReplaceHits(
            bookUrl = movingBooks.size,
            tocUrl = bookPlans.count { it.tocUrlChanged },
            chapterBookUrl = movingBooks.sumOf { appDb.bookChapterDao.getChapterCount(it.oldBookUrl) },
            chapterUrl = urlChanges.sumOf { it.count },
            chapterBaseUrl = baseUrlChanges.sumOf { it.count },
            chapterResourceUrl = resourceUrlChanges.sumOf { it.count },
        )
        // 冲突分类：不再禁止执行，而是分区展示
        val conflictItems = buildConflictItems(bookPlans)
        val duplicateChapters = urlChanges.mapNotNull { change ->
            val count = appDb.bookChapterDao.countChapterUrlConflicts(change.prefix, change.newPrefix)
            if (count > 0) DuplicateChapters(change.prefix, change.newPrefix, count) else null
        }
        this.conflictItems = conflictItems
        publishConflictItems()
        if (!hits.hasAny && conflictItems.isEmpty()) {
            return DomainReplaceCheckResult.Blocked(
                noHitsText(rule, allBooks, urlGroups, baseUrlGroups, resourceUrlGroups)
            )
        }
        return DomainReplaceCheckResult.Ready(
            DomainReplacePreview(
                rule = rule,
                hits = hits,
                authorityChanges = buildAuthorityChanges(urlChanges, baseUrlChanges, resourceUrlChanges),
                hostMatches = buildHostMatches(bookPlans, urlChanges, baseUrlChanges, resourceUrlChanges),
                booksToRename = movingBooks.map { it.book.name },
                conflictItems = conflictItems,
                duplicateChapters = duplicateChapters,
            )
        )
    }

    /**
     * 把「会搬 bookUrl 的书籍」按真实约束分类成冲突项。
     *
     * - `books` 主键 `bookUrl` 被另一本书占用 → 按证据分类（同书/同名不同源/不同书/本地书）；
     * - 同批两本书要搬去同一个地址 → 批内碰撞；
     * - 目标不存在且无批内碰撞 → 不是冲突，不产生条目。
     */
    private fun buildConflictItems(bookPlans: List<BookPlan>): List<ConflictItem> {
        val moving = bookPlans.filter { it.bookUrlChanged }
        val batchCollisionTargets = moving
            .groupBy { it.newBookUrl }
            .filterValues { it.size > 1 }
            .keys
        val items = mutableListOf<ConflictItem>()
        moving.forEach { plan ->
            val targetBook = appDb.bookDao.getBook(plan.newBookUrl)
            val batchCollides = plan.newBookUrl in batchCollisionTargets
            if (targetBook == null && !batchCollides) return@forEach
            val oldInfo = conflictBookOf(plan.book, needPaths = targetBook != null)
            val targetInfo = targetBook?.let { conflictBookOf(it, needPaths = true) }
            val kind = if (targetBook == null) {
                ConflictKind.BATCH_TARGET_COLLISION
            } else {
                DomainReplaceConflicts.classify(oldInfo, targetInfo!!, batchCollides)
            }
            items.add(ConflictItem(oldBook = oldInfo, targetBook = targetInfo, kind = kind))
        }
        return restoreStatuses(items)
    }

    /** 冲突清单里一行书籍信息；稳定 ID = bookUrl（books 表主键） */
    private fun conflictBookOf(book: Book, needPaths: Boolean): DomainReplaceConflicts.ConflictBook {
        val paths = if (needPaths) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
                .mapNotNull { DomainReplaceConflicts.pathOf(it.url) }
                .toSet()
        } else {
            emptySet()
        }
        return DomainReplaceConflicts.ConflictBook(
            bookUrl = book.bookUrl,
            name = book.name,
            author = book.author,
            origin = book.origin,
            isLocal = book.isLocal,
            chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl),
            chapterPaths = paths,
            hasProgress = book.durChapterIndex > 0 || book.durChapterPos > 0 || book.durChapterTime > 0,
        )
    }

    /**
     * 零命中时不能只说「没有记录」：把「输入被当成了什么」与「数据库里实际有什么」
     * 摆在一起，用户一眼就能看出是正则写法不符、被当成字面量，还是库里的地址
     * 根本不是这个域名——而不是把所有失败笼统归为「没有匹配域名的记录」。
     */
    private fun noHitsText(
        rule: UrlDomainReplacement.DomainRule,
        allBooks: List<Book>,
        vararg groups: List<DomainGroupRow>,
    ): String {
        val mode = context.getString(
            if (rule.literal) R.string.change_domain_mode_literal
            else R.string.change_domain_mode_regex
        )
        return buildString {
            appendLine(context.getString(R.string.change_domain_no_hits, rule.oldInput))
            appendLine()
            appendLine(context.getString(R.string.change_domain_no_hits_rule, mode, rule.pattern))
            val hosts = sampleHosts(allBooks, *groups)
            if (hosts.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.change_domain_no_hits_hosts))
                hosts.forEach {
                    appendLine(
                        context.getString(
                            R.string.change_domain_no_hits_host_line,
                            it.oldHost,
                            it.count
                        )
                    )
                }
            }
            appendLine()
            append(context.getString(R.string.change_domain_no_hits_hint))
        }
    }

    /** 数据库中现有主机名的分布（不按规则过滤），供零命中时对照排查 */
    private fun sampleHosts(
        allBooks: List<Book>,
        vararg groups: List<DomainGroupRow>,
    ): List<DomainHostMatch> {
        val counts = LinkedHashMap<String, Int>()
        fun add(host: String?, n: Int) {
            if (host.isNullOrEmpty()) return
            counts[host] = (counts[host] ?: 0) + n
        }
        groups.forEach { group ->
            group.forEach { add(UrlDomainReplacement.hostOfAuthority(it.authority), it.cnt) }
        }
        allBooks.forEach { book ->
            add(UrlDomainReplacement.hostOf(book.bookUrl), 1)
            add(UrlDomainReplacement.hostOf(book.tocUrl), 1)
        }
        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
            )
            .take(10)
            .map { DomainHostMatch(it.key, it.value) }
    }

    /**
     * 按「旧主机名」聚合命中次数，供预览展示「哪些主机名会被改写」。
     *
     * 规则把整段主机名换成固定新域名，所以每个旧主机名对应的新主机名都是 `rule.newDomain`；
     * 带端口的情况在 [DomainAuthorityChange] 里逐条列出。
     */
    private fun buildHostMatches(
        bookPlans: List<BookPlan>,
        vararg fieldChanges: List<ChapterFieldChange>,
    ): List<DomainHostMatch> {
        val counts = LinkedHashMap<String, Int>()
        fun add(host: String?, n: Int) {
            if (host.isNullOrEmpty()) return
            counts[host] = (counts[host] ?: 0) + n
        }
        fieldChanges.forEach { changes ->
            changes.forEach { change ->
                add(UrlDomainReplacement.hostOfAuthority(change.authority), change.count)
            }
        }
        bookPlans.forEach { plan ->
            if (plan.bookUrlChanged) add(UrlDomainReplacement.hostOf(plan.book.bookUrl), 1)
            if (plan.tocUrlChanged) add(UrlDomainReplacement.hostOf(plan.book.tocUrl), 1)
        }
        return counts.map { (host, count) -> DomainHostMatch(host, count) }
    }

    private fun buildAuthorityChanges(
        vararg fieldChanges: List<ChapterFieldChange>,
    ): List<DomainAuthorityChange> {
        val fields = arrayOf("chapters.url", "chapters.baseUrl", "chapters.resourceUrl")
        val result = mutableListOf<DomainAuthorityChange>()
        fieldChanges.forEachIndexed { index, changes ->
            changes.forEach { change ->
                result.add(
                    DomainAuthorityChange(
                        field = fields[index],
                        oldAuthority = change.authority,
                        newAuthority = change.newAuthority,
                        count = change.count,
                    )
                )
            }
        }
        return result
    }

    // ------------------------------------------------------------------ 执行

    private fun runDomainReplace(oldInput: String, newInput: String): DomainReplaceResult {
        val rule = when (val result = UrlDomainReplacement.normalize(oldInput, newInput)) {
            is UrlDomainReplacement.NormalizeResult.Failure ->
                return DomainReplaceResult(rule = null, abortReason = invalidReasonText(result.reason))

            is UrlDomainReplacement.NormalizeResult.Success -> result.rule
        }
        AppLog.put("批量替换域名开始：${rule.oldInput} → ${rule.newDomain}")
        domainReplaceProcessLiveData.postValue(
            context.getString(R.string.change_domain_stage_prepare)
        )
        var abortReason: String? = null
        var counts = DomainReplaceCounts()
        var planned: DomainReplacePlannedCounts? = null
        var pendingConflicts: List<ConflictItem> = emptyList()
        var progressFixCount = 0
        val movingBooks = mutableListOf<BookPlan>()

        appDb.runInTransaction {
            domainReplaceProcessLiveData.postValue(
                context.getString(R.string.change_domain_stage_update)
            )
            val bookPlans = planBooks(rule)
            val urlChanges = chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterUrl(), rule)
            val baseUrlChanges =
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterBaseUrl(), rule)
            val resourceUrlChanges =
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterResourceUrl(), rule)
            pendingConflicts = buildConflictItems(bookPlans)
            val conflictedUrls = pendingConflicts.map { it.oldBookUrl }.toSet()
            val plannedBooks = bookPlans.filter { it.changed && it.oldBookUrl !in conflictedUrls }
            val plan = DomainReplacePlan(
                urlChanges = urlChanges,
                baseUrlChanges = baseUrlChanges,
                resourceUrlChanges = resourceUrlChanges,
                bookUpdates = plannedBooks.map {
                    BookUrlUpdate(it.oldBookUrl, it.newBookUrl, it.newTocUrl)
                },
                bookMoves = plannedBooks.filter { it.bookUrlChanged }
                    .map { BookUrlMove(it.oldBookUrl, it.newBookUrl) },
            )
            if (plan.isEmpty) {
                abortReason = if (pendingConflicts.isEmpty()) {
                    context.getString(R.string.change_domain_no_hits, rule.oldInput)
                } else {
                    context.getString(
                        R.string.change_domain_abort_all_conflicted,
                        pendingConflicts.size
                    )
                }
                return@runInTransaction
            }

            // 计划数先落档（只含本次要写的无冲突部分）；与 SQL 真实影响行数是两条独立口径
            planned = DomainReplacePlannedCounts(
                books = plannedBooks.size,
                chapterBookUrl = plannedBooks.filter { it.bookUrlChanged }
                    .sumOf { appDb.bookChapterDao.getChapterCount(it.oldBookUrl) },
                chapterUrl = urlChanges.sumOf { it.count },
                chapterBaseUrl = baseUrlChanges.sumOf { it.count },
                chapterResourceUrl = resourceUrlChanges.sumOf { it.count },
            )

            // 章节去重会让同书章节行减少，阅读位置需要按幸存行修正：先快照计算再写入
            progressFixCount = applyDedupeProgressFixes(urlChanges)

            // 写入顺序与外键处理由 DomainReplaceExecutor 统一负责，与真实 SQLite 测试共用同一段逻辑
            counts = DomainReplaceExecutor.run(
                plan = plan,
                execSql = { appDb.openHelper.writableDatabase.execSQL(it) },
                apply = { statement ->
                    when (statement) {
                        is DomainReplaceStatement.ChapterDedupLift ->
                            appDb.bookChapterDao.liftChapterIndexes(
                                statement.prefix,
                                statement.newPrefix,
                                statement.offset,
                            )

                        is DomainReplaceStatement.ChapterUrlDedupe ->
                            appDb.bookChapterDao.deleteDuplicateChapterUrls(
                                statement.prefix,
                                statement.newPrefix,
                            )

                        is DomainReplaceStatement.ChapterDedupSettle ->
                            appDb.bookChapterDao.settleChapterIndexes(statement.offset)

                        is DomainReplaceStatement.ChapterUrl ->
                            appDb.bookChapterDao.replaceChapterUrlDomain(
                                statement.prefix,
                                statement.newPrefix,
                            )

                        is DomainReplaceStatement.ChapterBaseUrl ->
                            appDb.bookChapterDao.replaceChapterBaseUrlDomain(
                                statement.prefix,
                                statement.newPrefix,
                            )

                        is DomainReplaceStatement.ChapterResourceUrl ->
                            appDb.bookChapterDao.replaceChapterResourceUrlDomain(
                                statement.prefix,
                                statement.newPrefix,
                            )

                        is DomainReplaceStatement.BookUrls ->
                            appDb.bookDao.updateBookUrls(
                                statement.oldBookUrl,
                                statement.newBookUrl,
                                statement.newTocUrl,
                            )

                        is DomainReplaceStatement.ChapterBookUrl ->
                            appDb.bookChapterDao.replaceChapterBookUrl(
                                statement.oldBookUrl,
                                statement.newBookUrl,
                            )
                    }
                },
            )
            movingBooks.addAll(plannedBooks.filter { it.bookUrlChanged })
        }

        // runInTransaction 返回即已提交或回滚。这里用同一套匹配规则重查库里剩余命中，
        // 作为「实际更新是否彻底」的核对依据，而不是只看 SQL 返回值。
        domainReplaceProcessLiveData.postValue(
            context.getString(R.string.change_domain_stage_finish)
        )
        val remaining = if (abortReason == null) countRemainingHits(rule) else null
        val countMismatches = DomainReplaceAudit.compare(planned, counts).map {
            context.getString(
                R.string.change_domain_audit_mismatch,
                it.field,
                it.planned,
                it.actual
            )
        }

        // 待处理冲突进入冲突清单（状态与之前保留的一致），供结果弹窗与清单弹窗使用
        conflictItems = restoreStatuses(pendingConflicts)
        publishConflictItems()

        val result = DomainReplaceResult(
            rule = rule,
            booksUpdated = counts.books,
            chapterBookUrlUpdated = counts.chapterBookUrl,
            chapterUrlUpdated = counts.chapterUrl,
            chapterBaseUrlUpdated = counts.chapterBaseUrl,
            chapterResourceUrlUpdated = counts.chapterResourceUrl,
            chapterDuplicatesDeleted = counts.chapterUrlDuplicates,
            booksSkippedByConflict = pendingConflicts.size,
            progressFixCount = progressFixCount,
            planned = planned,
            countMismatches = countMismatches,
            remainingHits = remaining?.let { (book, chapter) -> book + chapter } ?: -1,
            remainingBookUrlHits = remaining?.first ?: -1,
            remainingChapterHits = remaining?.second ?: -1,
            conflictsPending = conflictItems,
            abortReason = abortReason,
        )
        if (abortReason != null) {
            AppLog.put("批量替换域名未执行：$abortReason")
            return result
        }
        if (countMismatches.isNotEmpty()) {
            AppLog.put(
                "批量替换域名计划与实际不一致\n${countMismatches.joinToString("\n")}"
            )
        } else {
            AppLog.put(
                "批量替换域名完成并提交：" +
                    "books=${counts.books} chapters.bookUrl=${counts.chapterBookUrl} " +
                    "chapters.url=${counts.chapterUrl} baseUrl=${counts.chapterBaseUrl} " +
                    "resourceUrl=${counts.chapterResourceUrl} " +
                    "去重章节=${counts.chapterUrlDuplicates} 进度修正=$progressFixCount " +
                    "待处理冲突=${pendingConflicts.size} " +
                    "剩余旧域名命中(bookUrl)=${remaining?.first} (章节)=${remaining?.second}"
            )
        }

        // 事务提交成功之后才碰文件系统与内存对象，避免回滚后留下改过名的缓存目录
        var renamed = 0
        var renameFailed = 0
        val readerRefreshed = mutableListOf<String>()
        val readerFailed = mutableListOf<String>()
        movingBooks.forEach { plan ->
            val newBook = plan.book.copy(bookUrl = plan.newBookUrl, tocUrl = plan.newTocUrl)
            kotlin.runCatching { BookHelp.updateCacheFolder(plan.book, newBook) }
                .onSuccess { moved -> if (moved) renamed++ }
                .onFailure {
                    renameFailed++
                    AppLog.put(
                        "批量替换域名：缓存目录改名失败《${plan.book.name}》\n${it.localizedMessage}",
                        it,
                        false
                    )
                }
            if (ReadBook.book?.bookUrl == plan.oldBookUrl) {
                if (refreshReaderIfHolding(plan, newBook)) {
                    readerRefreshed.add(plan.book.name)
                } else {
                    readerFailed.add(plan.book.name)
                }
            }
        }
        return result.copy(
            cacheFolderRenamed = renamed,
            cacheFolderFailed = renameFailed,
            readerRefreshedBooks = readerRefreshed,
            readerRefreshFailedBooks = readerFailed,
        )
    }

    /**
     * 章节去重会让同一本书的章节行减少，按位置记录的阅读进度需要重新定位。
     * 必须在写入前快照（事务内、`DomainReplaceExecutor.run` 之前），
     * 位置计算基于「删除 + 改写」后的最终章节序列。
     */
    private fun applyDedupeProgressFixes(urlChanges: List<ChapterFieldChange>): Int {
        var fixed = 0
        urlChanges.forEach { change ->
            val bookUrls = appDb.bookChapterDao.conflictChapterBooks(change.prefix, change.newPrefix)
            bookUrls.forEach { bookUrl ->
                val book = appDb.bookDao.getBook(bookUrl) ?: return@forEach
                val before = appDb.bookChapterDao.getChapterList(bookUrl)
                if (before.isEmpty()) return@forEach
                val deletedUrls = appDb.bookChapterDao.duplicateChapterUrlsForBook(
                    bookUrl, change.prefix, change.newPrefix,
                ).toSet()
                if (deletedUrls.isEmpty()) return@forEach
                val adjusted = DomainReplaceConflicts.adjustProgressAfterDedupe(
                    book.durChapterIndex,
                    before.map { it.url },
                    deletedUrls,
                ) ?: return@forEach
                if (adjusted.first == book.durChapterIndex) return@forEach
                val newTitle = adjusted.second?.let { url ->
                    before.firstOrNull { it.url == url }?.title
                }
                book.durChapterIndex = adjusted.first
                if (newTitle != null) book.durChapterTitle = newTitle
                appDb.bookDao.update(book)
                AppLog.put(
                    "批量替换域名：章节去重后《${book.name}》阅读位置修正为第 ${adjusted.first} 章"
                )
                fixed++
            }
        }
        return fixed
    }

    /**
     * 提交后核对：用同一套匹配规则重查，返回 (书籍地址剩余命中, 章节地址剩余命中)。
     * 书籍剩余命中 > 0 的正常来源是待处理冲突书籍（主记录未换址）；章节剩余命中应接近 0，
     * 大于 0 说明有章节地址没有按计划改写，必须显式报告。
     */
    private fun countRemainingHits(rule: UrlDomainReplacement.DomainRule): Pair<Int, Int> {
        val bookHits = planBooks(rule).count { it.changed }
        val chapterHits =
            chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterUrl(), rule)
                .sumOf { it.count } +
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterBaseUrl(), rule)
                    .sumOf { it.count } +
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterResourceUrl(), rule)
                    .sumOf { it.count }
        return bookHits to chapterHits
    }

    /**
     * 阅读器可能仍持有替换前的书籍对象。不刷新的话，返回阅读器后会继续用旧 bookUrl
     * 查章节，表现为「目录为空 / 正文加载失败」。
     *
     * 这里复用 ReadBook 自己的 upData（书籍数据变更入口）替换内存对象，不触发任何网络请求；
     * 替换前把内存中的阅读位置写回，避免未落库的进度被数据库旧值覆盖。
     */
    private fun refreshReaderIfHolding(plan: BookPlan, newBook: Book): Boolean {
        val current = ReadBook.book ?: return false
        if (current.bookUrl != plan.oldBookUrl) return false
        return kotlin.runCatching {
            newBook.durChapterIndex = ReadBook.durChapterIndex
            newBook.durChapterPos = ReadBook.durChapterPos
            ReadBook.upData(newBook)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 冲突清单操作

    /**
     * 跳过一条冲突：只改本地状态，不动数据库；跳过项在重新检查时恢复为待处理。
     */
    fun skipConflict(item: ConflictItem) {
        conflictItems = conflictItems.map {
            if (it.oldBookUrl == item.oldBookUrl) {
                it.copy(
                    status = ConflictStatus.SKIPPED,
                    note = context.getString(R.string.change_domain_conflict_skipped)
                )
            } else {
                it
            }
        }
        publishConflictItems()
    }

    /**
     * 重新检查一条冲突：按稳定 ID 重新读取两条记录的数据库现状。
     * 旧记录已消失 → 已解决；目标已释放 → 直接安全完成该书的替换；
     * 仍在 → 按最新证据重新分类。只读取相关记录，不做全库扫描。
     */
    fun recheckConflict(item: ConflictItem) {
        if (!domainReplaceRunning.compareAndSet(false, true)) {
            domainReplaceCheckLiveData.postValue(
                DomainReplaceCheckResult.Blocked(
                    context.getString(R.string.change_domain_task_running)
                )
            )
            return
        }
        domainReplaceBusy.value = true
        domainReplaceCoroutine?.cancel()
        domainReplaceCoroutine = execute {
            val old = appDb.bookDao.getBook(item.oldBookUrl)
            when {
                old == null -> ConflictActionResult(
                    item,
                    ConflictStatus.RESOLVED,
                    context.getString(R.string.change_domain_conflict_old_gone)
                )

                else -> {
                    val target = item.targetBookUrl?.let { appDb.bookDao.getBook(it) }
                    if (target == null) {
                        val rule = currentRule()
                        if (rule == null) {
                            ConflictActionResult(
                                item,
                                ConflictStatus.FAILED,
                                context.getString(R.string.change_domain_task_context_lost)
                            )
                        } else {
                            val result = replaceSingleBook(rule, old)
                            if (result.error != null) {
                                ConflictActionResult(item, ConflictStatus.FAILED, result.error)
                            } else {
                                ConflictActionResult(
                                    item,
                                    ConflictStatus.RESOLVED,
                                    context.getString(
                                        R.string.change_domain_conflict_replaced,
                                        result.counts.books,
                                        result.counts.chapterUrl,
                                    )
                                )
                            }
                        }
                    } else {
                        val oldInfo = conflictBookOf(old, needPaths = true)
                        val targetInfo = conflictBookOf(target, needPaths = true)
                        val kind = DomainReplaceConflicts.classify(oldInfo, targetInfo, false)
                        val note = if (kind != item.kind) {
                            context.getString(R.string.change_domain_conflict_kind_changed)
                        } else {
                            item.note
                        }
                        ConflictActionResult(
                            item.copy(kind = kind),
                            ConflictStatus.PENDING,
                            note
                        )
                    }
                }
            }
        }.onSuccess { action ->
            conflictItems = conflictItems.map {
                if (it.oldBookUrl == item.oldBookUrl) {
                    action.item.copy(status = action.status, note = action.note)
                } else {
                    it
                }
            }
            publishConflictItems()
        }.onError {
            if (it is CancellationException) return@onError
            AppLog.put("批量替换域名：重新检查冲突失败\n${it.stackTraceStr}", it, true)
            updateConflictStatus(item, ConflictStatus.FAILED, it.localizedMessage)
        }.onFinally {
            domainReplaceRunning.set(false)
            domainReplaceBusy.postValue(false)
        }
    }

    /**
     * A1 已确认同一本书的旧记录清理：保留新域名记录为主，先迁移旧记录中有价值的数据
     * （阅读进度、书架分组、排序与自定义信息、书签、阅读时长统计），再删除旧记录
     * （章节由外键 ON DELETE CASCADE 自动删除）。全部在用户确认后的单个事务内完成。
     */
    fun cleanupOldRecord(item: ConflictItem) {
        if (!item.canCleanupOld) return
        if (!domainReplaceRunning.compareAndSet(false, true)) {
            domainReplaceCheckLiveData.postValue(
                DomainReplaceCheckResult.Blocked(
                    context.getString(R.string.change_domain_task_running)
                )
            )
            return
        }
        domainReplaceBusy.value = true
        domainReplaceCoroutine?.cancel()
        domainReplaceCoroutine = execute {
            if (ReadBook.book?.bookUrl == item.oldBookUrl) {
                return@execute ConflictActionResult(
                    item,
                    ConflictStatus.FAILED,
                    context.getString(R.string.change_domain_conflict_reader_holding)
                )
            }
            var action: ConflictActionResult? = null
            var oldForCache: Book? = null
            appDb.runInTransaction {
                val old = appDb.bookDao.getBook(item.oldBookUrl)
                if (old == null) {
                    action = ConflictActionResult(
                        item,
                        ConflictStatus.RESOLVED,
                        context.getString(R.string.change_domain_conflict_old_gone)
                    )
                    return@runInTransaction
                }
                oldForCache = old
                val target = item.targetBookUrl?.let { appDb.bookDao.getBook(it) }
                if (target == null) {
                    action = ConflictActionResult(
                        item,
                        ConflictStatus.PENDING,
                        context.getString(R.string.change_domain_conflict_target_gone_hint)
                    )
                    return@runInTransaction
                }
                // 复核证据：结论可能已过期（书源更新、目录变化），过期就不删
                val oldInfo = conflictBookOf(old, needPaths = true)
                val targetInfo = conflictBookOf(target, needPaths = true)
                val kind = DomainReplaceConflicts.classify(oldInfo, targetInfo, false)
                if (kind != ConflictKind.SAME_BOOK_CONFIRMED) {
                    action = ConflictActionResult(
                        item,
                        ConflictStatus.PENDING,
                        context.getString(R.string.change_domain_cleanup_evidence_changed)
                    )
                    return@runInTransaction
                }
                val targetChapters = appDb.bookChapterDao.getChapterList(target.bookUrl)
                val oldChapters = appDb.bookChapterDao.getChapterList(old.bookUrl)
                val migration = migrateFromOldRecord(old, target, oldChapters, targetChapters)
                appDb.bookDao.update(migration.book)
                val bookmarksMoved =
                    appDb.bookmarkDao.rekeyOwner(old.name, old.author, target.name, target.author)
                val recordsMoved = appDb.readRecordDao.rekeyOwner(old.name, target.name)
                // 章节由 FK ON DELETE CASCADE 随旧记录删除
                appDb.bookDao.delete(old)
                action = ConflictActionResult(
                    item,
                    ConflictStatus.RESOLVED,
                    context.getString(
                        R.string.change_domain_cleanup_done,
                        if (migration.progressMigrated) {
                            context.getString(R.string.change_domain_cleanup_progress_moved)
                        } else {
                            migration.progressNote
                                ?: context.getString(R.string.change_domain_cleanup_progress_kept)
                        },
                        bookmarksMoved,
                        recordsMoved,
                        oldChapters.size,
                    )
                )
            }
            // 事务提交后才能碰文件系统：旧记录的缓存目录按 bookUrl 命名，删除记录后成为孤儿
            oldForCache?.let { old ->
                kotlin.runCatching { BookHelp.clearCache(old) }
                    .onFailure {
                        AppLog.put(
                            "批量替换域名：旧记录缓存目录清理失败《${old.name}》\n${it.localizedMessage}",
                            it, false
                        )
                    }
            }
            action ?: ConflictActionResult(
                item,
                ConflictStatus.FAILED,
                context.getString(R.string.change_domain_cleanup_failed)
            )
        }.onSuccess { action ->
            conflictItems = conflictItems.map {
                if (it.oldBookUrl == item.oldBookUrl) {
                    it.copy(status = action.status, note = action.note)
                } else {
                    it
                }
            }
            publishConflictItems()
        }.onError {
            if (it is CancellationException) return@onError
            AppLog.put("批量替换域名：清理旧记录失败\n${it.stackTraceStr}", it, true)
            updateConflictStatus(item, ConflictStatus.FAILED, it.localizedMessage)
        }.onFinally {
            domainReplaceRunning.set(false)
            domainReplaceBusy.postValue(false)
        }
    }

    /**
     * 旧记录 → 保留的新记录 的数据迁移规则（对齐项目既有的换源迁移 [Book.migrateTo]）：
     * - 阅读进度：目标没有进度时迁移；优先按章节路径精确映射（域名迁移路径不变），
     *   失败回退 [BookHelp.getDurChapter] 的标题相似度匹配（与换源一致）；
     *   目标已有进度则保留目标的，绝不覆盖更新的进度。
     * - 书架分组：按位合并（group 是位掩码），不丢任何一侧的分组。
     * - 排序与自定义信息（order / customCoverUrl / customIntro / customTag / readConfig）：
     *   目标为默认值时继承旧的；canUpdate 跟随旧记录（用户对这本书的更新开关）。
     * - 章节目录与 variable：不迁移。新记录按新书源重新获取目录；旧章节行随旧记录删除。
     */
    private fun migrateFromOldRecord(
        old: Book,
        target: Book,
        oldChapters: List<BookChapter>,
        targetChapters: List<BookChapter>,
    ): MigrationOutcome {
        val newBook = target.copy()
        val oldHasProgress =
            old.durChapterIndex > 0 || old.durChapterPos > 0 || old.durChapterTime > 0
        val targetHasProgress =
            target.durChapterIndex > 0 || target.durChapterPos > 0 || target.durChapterTime > 0
        var progressMigrated = false
        var progressNote: String? = null
        if (oldHasProgress && !targetHasProgress) {
            if (targetChapters.isEmpty()) {
                progressNote = context.getString(R.string.change_domain_cleanup_progress_no_toc)
            } else {
                // 路径列表必须与 targetChapters 下标一一对应：非 http 地址给唯一占位路径
                val mapped = DomainReplaceConflicts.mapProgressByPath(
                    old.durChapterIndex,
                    oldChapters.map { it.url },
                    targetChapters.map {
                        DomainReplaceConflicts.pathOf(it.url) ?: "::nonhttp-${it.index}"
                    },
                )
                val index = (mapped ?: BookHelp.getDurChapter(
                    old.durChapterIndex,
                    old.durChapterTitle,
                    targetChapters,
                    old.totalChapterNum,
                )).coerceIn(0, targetChapters.size - 1)
                newBook.durChapterIndex = index
                newBook.durChapterPos = old.durChapterPos
                newBook.durChapterTime = old.durChapterTime
                newBook.durChapterTitle = targetChapters[index].getDisplayTitle(
                    ContentProcessor.get(newBook.name, newBook.origin).getTitleReplaceRules(),
                    old.getUseReplaceRule(),
                )
                progressMigrated = true
            }
        } else if (oldHasProgress && targetHasProgress) {
            progressNote = context.getString(R.string.change_domain_cleanup_progress_kept)
        }
        if (old.group != 0L) {
            newBook.group = newBook.group or old.group
        }
        if (newBook.order == 0) newBook.order = old.order
        if (newBook.customCoverUrl == null) newBook.customCoverUrl = old.customCoverUrl
        if (newBook.customIntro == null) newBook.customIntro = old.customIntro
        if (newBook.customTag == null) newBook.customTag = old.customTag
        if (newBook.readConfig == null) newBook.readConfig = old.readConfig
        newBook.canUpdate = old.canUpdate
        return MigrationOutcome(newBook, progressMigrated, progressNote)
    }

    private fun updateConflictStatus(item: ConflictItem, status: ConflictStatus, note: String?) {
        conflictItems = conflictItems.map {
            if (it.oldBookUrl == item.oldBookUrl) it.copy(status = status, note = note) else it
        }
        publishConflictItems()
    }

    /**
     * 冲突消失后的单本完成替换：重新校验目标空闲 → 去重 → 三列章节地址 + 书籍主键 +
     * 章节归属，全部在一个独立事务内；失败只影响这一条，不回滚已提交的其他结果。
     */
    private fun replaceSingleBook(
        rule: UrlDomainReplacement.DomainRule,
        oldBook: Book,
    ): SingleResult {
        val newBookUrl = UrlDomainReplacement.replaceDomain(oldBook.bookUrl, rule)
            ?: return SingleResult(
                error = context.getString(R.string.change_domain_single_no_match)
            )
        val newTocUrl = UrlDomainReplacement.replaceDomain(oldBook.tocUrl, rule) ?: oldBook.tocUrl
        var error: String? = null
        var chapterUrl = 0
        var chapterUrlDuplicates = 0
        var chapterBaseUrl = 0
        var chapterResourceUrl = 0
        var books = 0
        var chapterBookUrl = 0
        appDb.runInTransaction {
            if (appDb.bookDao.has(newBookUrl)) {
                error = context.getString(
                    R.string.change_domain_conflict_book_occupied,
                    oldBook.name,
                    newBookUrl
                )
                return@runInTransaction
            }
            val urlChanges =
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterUrl(), rule)
            val baseUrlChanges =
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterBaseUrl(), rule)
            val resourceUrlChanges =
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterResourceUrl(), rule)
            // 先修正阅读位置（快照在删除之前），再删除撞键的旧域名重复行（保留新域名行）、
            // 紧凑重排 index，再做改写
            applySingleBookDedupeProgressFix(oldBook.bookUrl, urlChanges)
            urlChanges.forEach { change ->
                appDb.bookChapterDao.liftChapterIndexesForBook(
                    oldBook.bookUrl, change.prefix, change.newPrefix,
                    DomainReplaceExecutor.INDEX_COMPACT_OFFSET,
                )
                chapterUrlDuplicates += appDb.bookChapterDao.deleteDuplicateChapterUrlsForBook(
                    oldBook.bookUrl, change.prefix, change.newPrefix,
                )
                appDb.bookChapterDao.settleChapterIndexesForBook(
                    oldBook.bookUrl, DomainReplaceExecutor.INDEX_COMPACT_OFFSET,
                )
                chapterUrl += appDb.bookChapterDao.replaceChapterUrlDomainForBook(
                    oldBook.bookUrl, change.prefix, change.newPrefix,
                )
            }
            baseUrlChanges.forEach { change ->
                chapterBaseUrl += appDb.bookChapterDao.replaceChapterBaseUrlDomainForBook(
                    oldBook.bookUrl, change.prefix, change.newPrefix,
                )
            }
            resourceUrlChanges.forEach { change ->
                chapterResourceUrl += appDb.bookChapterDao.replaceChapterResourceUrlDomainForBook(
                    oldBook.bookUrl, change.prefix, change.newPrefix,
                )
            }
            appDb.openHelper.writableDatabase.execSQL(DomainReplaceExecutor.DEFER_FOREIGN_KEYS)
            books = appDb.bookDao.updateBookUrls(oldBook.bookUrl, newBookUrl, newTocUrl)
            chapterBookUrl =
                appDb.bookChapterDao.replaceChapterBookUrl(oldBook.bookUrl, newBookUrl)
        }
        val counts = DomainReplaceCounts(
            chapterUrl = chapterUrl,
            chapterBaseUrl = chapterBaseUrl,
            chapterResourceUrl = chapterResourceUrl,
            books = books,
            chapterBookUrl = chapterBookUrl,
            chapterUrlDuplicates = chapterUrlDuplicates,
        )
        var cacheRenamed = false
        if (error == null && counts.books > 0) {
            val newBook = oldBook.copy(bookUrl = newBookUrl, tocUrl = newTocUrl)
            cacheRenamed = kotlin.runCatching {
                BookHelp.updateCacheFolder(oldBook, newBook)
            }.getOrDefault(false)
            if (ReadBook.book?.bookUrl == oldBook.bookUrl) {
                refreshReaderIfHolding(
                    BookPlan(oldBook, newBookUrl, newTocUrl),
                    newBook,
                )
            }
        }
        return SingleResult(counts, error, cacheRenamed)
    }

    /** [applyDedupeProgressFixes] 的单书版本（只处理指定书籍的章节） */
    private fun applySingleBookDedupeProgressFix(
        bookUrl: String,
        urlChanges: List<ChapterFieldChange>,
    ) {
        urlChanges.forEach { change ->
            val deletedUrls = appDb.bookChapterDao.duplicateChapterUrlsForBook(
                bookUrl, change.prefix, change.newPrefix,
            ).toSet()
            if (deletedUrls.isEmpty()) return@forEach
            val book = appDb.bookDao.getBook(bookUrl) ?: return@forEach
            val before = appDb.bookChapterDao.getChapterList(bookUrl)
            if (before.isEmpty()) return@forEach
            val adjusted = DomainReplaceConflicts.adjustProgressAfterDedupe(
                book.durChapterIndex,
                before.map { it.url },
                deletedUrls,
            ) ?: return@forEach
            if (adjusted.first == book.durChapterIndex) return@forEach
            val newTitle = adjusted.second?.let { url ->
                before.firstOrNull { it.url == url }?.title
            }
            book.durChapterIndex = adjusted.first
            if (newTitle != null) book.durChapterTitle = newTitle
            appDb.bookDao.update(book)
        }
    }

    private fun invalidReasonText(reason: UrlDomainReplacement.InvalidReason): String {
        return context.getString(
            when (reason) {
                UrlDomainReplacement.InvalidReason.EMPTY_OLD -> R.string.change_domain_empty_old
                UrlDomainReplacement.InvalidReason.EMPTY_NEW -> R.string.change_domain_empty_new
                UrlDomainReplacement.InvalidReason.INVALID_OLD_REGEX ->
                    R.string.change_domain_invalid_old_regex

                UrlDomainReplacement.InvalidReason.OLD_REGEX_TOO_BROAD ->
                    R.string.change_domain_old_regex_too_broad

                UrlDomainReplacement.InvalidReason.INVALID_NEW -> R.string.change_domain_invalid_new
                UrlDomainReplacement.InvalidReason.SAME_AS_NEW_DOMAIN ->
                    R.string.change_domain_same_as_new
            }
        )
    }

    companion object {
        private const val KEY_OLD_INPUT = "domainReplaceOldInput"
        private const val KEY_NEW_INPUT = "domainReplaceNewInput"
        private const val KEY_STATUSES = "domainReplaceStatuses"
    }
}

/** 单本书完成替换的结果 */
internal class SingleResult(
    val counts: DomainReplaceCounts = DomainReplaceCounts(),
    val error: String? = null,
    val cacheRenamed: Boolean = false,
)

/** A1 清理的数据迁移结果（book 为迁移后应落库的保留记录） */
internal class MigrationOutcome(
    val book: Book,
    val progressMigrated: Boolean,
    val progressNote: String?,
)

/** 预览时按「旧主机名」聚合的命中分布 */
data class DomainHostMatch(
    val oldHost: String,
    /** 命中次数（按字段计，同一行可能命中多个字段） */
    val count: Int,
)

/** 预览结果（只读，不修改数据库） */
data class DomainReplacePreview(
    val rule: UrlDomainReplacement.DomainRule,
    val hits: DomainReplaceHits,
    val authorityChanges: List<DomainAuthorityChange>,
    /** 匹配到的不同旧主机名及其命中次数 */
    val hostMatches: List<DomainHostMatch>,
    /** bookUrl 会变的书籍名，用于提示缓存目录会一起改名 */
    val booksToRename: List<String>,
    /** 待处理冲突（已分类；不再禁止执行，只影响展示与执行范围） */
    val conflictItems: List<ConflictItem>,
    /** 章节 (url, bookUrl) 主键冲突的分布（执行时自动去重并单独计数） */
    val duplicateChapters: List<DuplicateChapters>,
)

/** 某个 (prefix, newPrefix) 分组下将被自动去重的重复章节数 */
data class DuplicateChapters(
    val prefix: String,
    val newPrefix: String,
    val count: Int,
)

/** 执行结果（数量全部来自 SQL 实际影响行数） */
data class DomainReplaceResult(
    val rule: UrlDomainReplacement.DomainRule?,
    val booksUpdated: Int = 0,
    val chapterBookUrlUpdated: Int = 0,
    val chapterUrlUpdated: Int = 0,
    val chapterBaseUrlUpdated: Int = 0,
    val chapterResourceUrlUpdated: Int = 0,
    /** 因章节主键冲突被删除的旧域名重复章节数 */
    val chapterDuplicatesDeleted: Int = 0,
    /** 因冲突被跳过、未完成替换的书籍数 */
    val booksSkippedByConflict: Int = 0,
    /** 章节去重后阅读位置被修正的书籍数 */
    val progressFixCount: Int = 0,
    val cacheFolderRenamed: Int = 0,
    val cacheFolderFailed: Int = 0,
    val readerRefreshedBooks: List<String> = emptyList(),
    /** 正在阅读、但内存对象刷新失败的书；必须提示用户退出重进，否则进度可能写不回库 */
    val readerRefreshFailedBooks: List<String> = emptyList(),
    /** 执行阶段用与预览同口径算出的计划数量（只含无冲突部分），用于与实际影响行数对照 */
    val planned: DomainReplacePlannedCounts? = null,
    /** 计划数与实际影响行数不一致的字段描述（空 = 完全一致） */
    val countMismatches: List<String> = emptyList(),
    /** 提交后用同一规则重查的剩余旧域名命中总数；-1 表示未核对（未执行写库） */
    val remainingHits: Int = -1,
    /** 剩余命中中的书籍地址部分（待处理冲突书籍的正常来源） */
    val remainingBookUrlHits: Int = -1,
    /** 剩余命中中的章节地址部分（正常应为 0，大于 0 必须报告） */
    val remainingChapterHits: Int = -1,
    /** 待处理冲突清单（部分完成状态；须在结果中如实展示） */
    val conflictsPending: List<ConflictItem> = emptyList(),
    /** 非空表示本次没有写入任何数据（输入非法 / 无命中 / 全部冲突 / 事务失败） */
    val abortReason: String? = null,
)
