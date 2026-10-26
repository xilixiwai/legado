package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.dao.DomainGroupRow
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookUrlMove
import io.legado.app.help.book.BookUrlUpdate
import io.legado.app.help.book.ChapterFieldChange
import io.legado.app.help.book.DomainReplaceAudit
import io.legado.app.help.book.DomainReplaceCounts
import io.legado.app.help.book.DomainReplaceExecutor
import io.legado.app.help.book.DomainReplacePlan
import io.legado.app.help.book.DomainReplacePlannedCounts
import io.legado.app.help.book.DomainReplaceStatement
import io.legado.app.help.book.UrlDomainReplacement
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean


/**
 * 「批量替换域名」的命中统计，按数据库字段拆分。
 *
 * 预览与执行结果都用它汇报，保证「预览看到的字段」和「实际改的字段」是同一组。
 */
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
    /** 非空表示存在主键冲突，不允许执行 */
    val conflicts: List<String>,
)

/** 执行结果（数量全部来自 SQL 实际影响行数） */
data class DomainReplaceResult(
    val rule: UrlDomainReplacement.DomainRule?,
    val booksUpdated: Int = 0,
    val chapterBookUrlUpdated: Int = 0,
    val chapterUrlUpdated: Int = 0,
    val chapterBaseUrlUpdated: Int = 0,
    val chapterResourceUrlUpdated: Int = 0,
    val cacheFolderRenamed: Int = 0,
    val cacheFolderFailed: Int = 0,
    val readerRefreshedBooks: List<String> = emptyList(),
    /** 正在阅读、但内存对象刷新失败的书；必须提示用户退出重进，否则进度可能写不回库 */
    val readerRefreshFailedBooks: List<String> = emptyList(),
    /** 执行阶段用与预览同口径算出的计划数量，用于与实际影响行数对照 */
    val planned: DomainReplacePlannedCounts? = null,
    /** 计划数与实际影响行数不一致的字段描述（空 = 完全一致） */
    val countMismatches: List<String> = emptyList(),
    /** 提交后用同一规则重查的剩余旧域名命中数；-1 表示未核对（未执行写库） */
    val remainingHits: Int = -1,
    /** 非空表示本次没有写入任何数据（输入非法 / 无命中 / 存在冲突 / 事务失败） */
    val abortReason: String? = null,
)

/** 「检查」结果：要么给出可执行的预览，要么给出不执行的原因 */
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



class BookshelfManageViewModel(application: Application) : BaseViewModel(application) {
    var groupId: Long = -1L
    var groupName: String? = null
    val batchChangeSourceState = MutableLiveData<Boolean>()
    val batchChangeSourceProcessLiveData = MutableLiveData<String>()
    var batchChangeSourceCoroutine: Coroutine<Unit>? = null

    val domainReplaceBusy = MutableLiveData<Boolean>()

    /** 执行阶段文字（准备 / 更新数据库 / 完成事务并核对），由 WaitDialog 展示 */
    val domainReplaceProcessLiveData = MutableLiveData<String>()

    /** 一次性事件：UI 消费后置空，避免旋转屏幕时重复弹窗 */
    val domainReplaceCheckLiveData = MutableLiveData<DomainReplaceCheckResult?>()
    val domainReplaceResultLiveData = MutableLiveData<DomainReplaceResult?>()
    var domainReplaceCoroutine: Coroutine<*>? = null

    /**
     * 检查/执行互斥标记：执行中的数据库事务无法安全中断，
     * 任务进行期间禁止再次提交检查或执行，防止重复执行与「取消后无反馈」。
     */
    private val domainReplaceRunning = AtomicBoolean(false)

    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        execute {
            val array = Array(books.size) {
                books[it].copy(canUpdate = canUpdate).apply {
                    if (!canUpdate) {
                        removeType(BookType.updateError)
                    }
                }
            }
            appDb.bookDao.update(*array)
        }
    }

    fun updateBook(vararg book: Book) {
        execute {
            appDb.bookDao.update(*book)
        }
    }

    fun deleteBook(books: List<Book>, deleteOriginal: Boolean = false) {
        execute {
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach {
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                }
            }
        }
    }

    fun saveAllUseBookSourceToFile(success: (file: File) -> Unit) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun changeSource(books: List<Book>, source: BookSource) {
        batchChangeSourceCoroutine?.cancel()
        batchChangeSourceCoroutine = execute {
            val changeSourceDelay = AppConfig.batchChangeSourceDelay * 1000L
            books.forEachIndexed { index, book ->
                batchChangeSourceProcessLiveData.postValue("${index + 1} / ${books.size}")
                if (book.isLocal) return@forEachIndexed
                if (book.origin == source.bookSourceUrl) return@forEachIndexed
                val newBook = WebBook.preciseSearchAwait(source, book.name, book.author)
                    .onFailure {
                        AppLog.put("搜索书籍出错\n${it.localizedMessage}", it, true)
                    }.getOrNull() ?: return@forEachIndexed
                kotlin.runCatching {
                    if (newBook.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(source, newBook)
                    }
                }.onFailure {
                    AppLog.put("获取书籍详情出错\n${it.localizedMessage}", it, true)
                    return@forEachIndexed
                }
                WebBook.getChapterListAwait(source, newBook)
                    .onFailure {
                        AppLog.put("获取目录出错\n${it.localizedMessage}", it, true)
                    }.getOrNull()?.let { toc ->
                        book.migrateTo(newBook, toc)
                        book.removeType(BookType.updateError)
                        appDb.bookDao.insert(newBook)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                delay(changeSourceDelay)
            }
        }.onStart {
            batchChangeSourceState.postValue(true)
        }.onFinally {
            batchChangeSourceState.postValue(false)
        }
    }

    // ============================================================================================
    // 批量替换域名（全数据库）
    //
    // 与旧「批量换源域名」的本质区别：不再按书籍、书源逐本迁移，而是把「旧域名 → 新域名」
    // 当作一次全库维护操作。每个字段独立匹配，因此「bookUrl 已是新域名、tocUrl 仍是旧域名」
    // 这类部分完成的状态能被继续修复，而不会整本书被跳过。
    //
    // 预览与执行共用 [planBooks] / [chapterFieldChanges] 与同一批 SQL，保证数量口径一致。
    // ============================================================================================

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

    /** 用户确认后执行；所有写入在一个事务内完成 */
    fun executeDomainReplace(oldInput: String, newInput: String) {
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
            runDomainReplace(oldInput, newInput)
        }.onStart {
            domainReplaceBusy.postValue(true)
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
        if (!hits.hasAny) {
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
                conflicts = collectConflicts(bookPlans, urlChanges),
            )
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

    /**
     * 更新前的主键冲突预检。
     *
     * chapters 的主键是 `(url, bookUrl)`，改写 url 后可能与「同一本书里已经是新域名的章节」撞主键；
     * books 的主键是 bookUrl，改写后可能与书架里另一本书撞主键。
     * 只要存在冲突就整体放弃，不静默跳过、不留下半完成状态。
     */
    private fun collectConflicts(
        bookPlans: List<BookPlan>,
        urlChanges: List<ChapterFieldChange>,
    ): List<String> {
        val conflicts = mutableListOf<String>()
        val movingBooks = bookPlans.filter { it.bookUrlChanged }
        movingBooks.groupBy { it.newBookUrl }
            .filterValues { it.size > 1 }
            .forEach { (target, books) ->
                conflicts.add(
                    context.getString(
                        R.string.change_domain_conflict_books_duplicate,
                        target,
                        books.joinToString { it.book.name },
                    )
                )
            }
        movingBooks.forEach { plan ->
            if (appDb.bookDao.has(plan.newBookUrl)) {
                conflicts.add(
                    context.getString(
                        R.string.change_domain_conflict_book_occupied,
                        plan.book.name,
                        plan.newBookUrl,
                    )
                )
            }
        }
        urlChanges.forEach { change ->
            val count = appDb.bookChapterDao.countChapterUrlConflicts(
                prefix = change.prefix,
                newPrefix = change.newPrefix,
            )
            if (count > 0) {
                conflicts.add(
                    context.getString(
                        R.string.change_domain_conflict_chapter,
                        change.prefix,
                        count,
                    )
                )
            }
        }
        return conflicts
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
            val plannedBooks = bookPlans.filter { it.changed }
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
                abortReason = context.getString(R.string.change_domain_no_hits, rule.oldInput)
                return@runInTransaction
            }
            // 事务内重新预检：即使预览已经过期、数据被别的流程改过，也不会带着过期结论直接写
            val conflicts = collectConflicts(bookPlans, urlChanges)
            if (conflicts.isNotEmpty()) {
                abortReason = context.getString(
                    R.string.change_domain_abort_conflict,
                    conflicts.joinToString("\n"),
                )
                return@runInTransaction
            }

            // 计划数先落档：与 SQL 真实影响行数是两条独立口径，事后对照可发现语义漂移
            planned = DomainReplacePlannedCounts(
                books = plannedBooks.size,
                chapterBookUrl = plannedBooks.filter { it.bookUrlChanged }
                    .sumOf { appDb.bookChapterDao.getChapterCount(it.oldBookUrl) },
                chapterUrl = urlChanges.sumOf { it.count },
                chapterBaseUrl = baseUrlChanges.sumOf { it.count },
                chapterResourceUrl = resourceUrlChanges.sumOf { it.count },
            )

            // 写入顺序与外键处理由 DomainReplaceExecutor 统一负责，与真实 SQLite 测试共用同一段逻辑
            counts = DomainReplaceExecutor.run(
                plan = plan,
                execSql = { appDb.openHelper.writableDatabase.execSQL(it) },
                apply = { statement ->
                    when (statement) {
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
        val remainingHits = if (abortReason == null) countRemainingHits(rule) else -1
        val countMismatches = DomainReplaceAudit.compare(planned, counts).map {
            context.getString(
                R.string.change_domain_audit_mismatch,
                it.field,
                it.planned,
                it.actual
            )
        }

        val result = DomainReplaceResult(
            rule = rule,
            booksUpdated = counts.books,
            chapterBookUrlUpdated = counts.chapterBookUrl,
            chapterUrlUpdated = counts.chapterUrl,
            chapterBaseUrlUpdated = counts.chapterBaseUrl,
            chapterResourceUrlUpdated = counts.chapterResourceUrl,
            planned = planned,
            countMismatches = countMismatches,
            remainingHits = remainingHits,
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
                    "resourceUrl=${counts.chapterResourceUrl} 剩余旧域名命中=$remainingHits"
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
     * 提交后核对：用同一套匹配规则重查，返回库里仍会命中的记录数。
     * 替换彻底时应为 0（规则幂等有测试保证）；大于 0 说明仍有旧域名残留，必须报告。
     */
    private fun countRemainingHits(rule: UrlDomainReplacement.DomainRule): Int {
        val bookHits = planBooks(rule).count { it.changed }
        val chapterHits =
            chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterUrl(), rule)
                .sumOf { it.count } +
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterBaseUrl(), rule)
                    .sumOf { it.count } +
                chapterFieldChanges(appDb.bookChapterDao.domainGroupsOfChapterResourceUrl(), rule)
                    .sumOf { it.count }
        return bookHits + chapterHits
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

    fun clearCache(books: List<Book>) {
        execute {
            books.forEach {
                BookHelp.clearCache(it)
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

}