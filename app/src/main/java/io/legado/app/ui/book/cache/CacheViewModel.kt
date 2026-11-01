package io.legado.app.ui.book.cache

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ChapterCacheMigrator
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.sendValue
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx
import java.io.File
import kotlin.collections.set


class CacheViewModel(application: Application) : BaseViewModel(application) {
    val upAdapterLiveData = MutableLiveData<String>()

    private var loadChapterCoroutine: Coroutine<Unit>? = null
    val cacheChapters = hashMapOf<String, HashSet<String>>()

    /** 旧版正文缓存扫描结果（先扫描统计，不修改任何文件） */
    val legacyScanData = MutableLiveData<ChapterCacheMigrator.ScanSummary>()

    /** 迁移执行进度 */
    val legacyProgressData = MutableLiveData<LegacyMigrateProgress>()

    /** 迁移完成（含完成后重扫的磁盘实况） */
    val legacyDoneData = MutableLiveData<ChapterCacheMigrator.ScanSummary>()

    private var legacyMigrateCoroutine: Coroutine<ChapterCacheMigrator.ScanSummary>? = null

    fun loadCacheFiles(books: List<Book>) {
        loadChapterCoroutine?.cancel()
        loadChapterCoroutine = execute {
            books.forEach { book ->
                if (!book.isLocal && !cacheChapters.contains(book.bookUrl)) {
                    val chapterCaches = hashSetOf<String>()
                    val cacheNames = BookHelp.getChapterFiles(book)
                    if (cacheNames.isNotEmpty()) {
                        appDb.bookChapterDao.getChapterList(book.bookUrl).also {
                            book.totalChapterNum = it.size
                        }.forEach { chapter ->
                            // 新/旧缓存键任一命中即视为已缓存，与 hasContent 口径一致
                            if (cacheNames.contains(chapter.getFileName())
                                || cacheNames.contains(chapter.getLegacyFileName())
                                || chapter.isVolume
                            ) {
                                chapterCaches.add(chapter.url)
                            }
                        }
                    }
                    cacheChapters[book.bookUrl] = chapterCaches
                    upAdapterLiveData.sendValue(book.bookUrl)
                }
                ensureActive()
            }
        }
    }

    /**
     * 扫描旧版章节正文缓存：只读磁盘与章节列表，不修改任何文件。
     */
    fun scanLegacyCache() {
        execute {
            scanLegacyCacheSync()
        }.onSuccess { summary ->
            legacyScanData.sendValue(summary)
        }.onError {
            AppLog.put("扫描旧版章节缓存失败\n${it.localizedMessage}", it)
            appCtx.toastOnUi("扫描旧版章节缓存失败:${it.localizedMessage}")
        }
    }

    /**
     * 执行旧版缓存迁移：逐文件处理，完成后重新扫描核实磁盘实况。
     * 取消粒度为书（单个文件的重命名是原子的，中断不会产生半成品文件）。
     */
    fun startLegacyMigration(includeMoved: Boolean) {
        legacyMigrateCoroutine?.cancel()
        legacyMigrateCoroutine = execute {
            val summary = scanLegacyCacheSync()
            val totalTargets = summary.safe + if (includeMoved) summary.uniqueMoved else 0
            var doneAll = 0
            var renamedAll = 0
            var skippedAll = 0
            var failedAll = 0
            summary.bookPlans.forEach { plan ->
                ensureActive()
                val bookTargets = plan.items.count {
                    it.action == ChapterCacheMigrator.Action.RENAME_SAFE
                        || (includeMoved && it.action == ChapterCacheMigrator.Action.RENAME_UNIQUE_MOVED)
                }
                if (bookTargets == 0) {
                    return@forEach
                }
                val beforeRenamed = renamedAll
                val beforeSkipped = skippedAll
                val beforeFailed = failedAll
                val stats = ChapterCacheMigrator.execute(plan, includeMoved) { done, s ->
                    legacyProgressData.postValue(
                        LegacyMigrateProgress(
                            plan.bookName,
                            doneAll + done,
                            totalTargets,
                            beforeRenamed + s.renamed,
                            beforeSkipped + s.skipped,
                            beforeFailed + s.failed
                        )
                    )
                }
                doneAll += bookTargets
                renamedAll += stats.renamed
                skippedAll += stats.skipped
                failedAll += stats.failed
            }
            // 完成后重新扫描，展示迁移后的真实磁盘状态
            scanLegacyCacheSync()
        }.onSuccess { summary ->
            legacyDoneData.sendValue(summary)
        }.onError {
            if (it !is kotlinx.coroutines.CancellationException) {
                AppLog.put("迁移旧版章节缓存失败\n${it.localizedMessage}", it)
                appCtx.toastOnUi("迁移旧版章节缓存失败:${it.localizedMessage}")
            }
        }
    }

    fun cancelLegacyMigration() {
        legacyMigrateCoroutine?.cancel()
    }

    private suspend fun scanLegacyCacheSync(): ChapterCacheMigrator.ScanSummary {
        val books = appDb.bookDao.all
        val cacheRoot = File(BookHelp.cachePath)
        val validFolders = books.mapTo(hashSetOf()) { it.getFolderName() }
        // 已删除书籍的缓存目录不扫描、不修改，仅计数展示
        val deletedBookDirs = cacheRoot.listFiles()
            ?.count { !validFolders.contains(it.name) } ?: 0
        var booksWithDir = 0
        var nrFlagFiles = 0
        val bookPlans = mutableListOf<ChapterCacheMigrator.BookPlan>()
        books.forEach { book ->
            currentCoroutineContext().ensureActive()
            val bookDir = File(cacheRoot, book.getFolderName())
            if (!bookDir.isDirectory) {
                return@forEach
            }
            booksWithDir++
            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            val plan = ChapterCacheMigrator.plan(book.bookUrl, book.name, bookDir, chapters)
            nrFlagFiles += plan.nrFlagFiles
            if (plan.total > 0 || plan.nrFlagFiles > 0) {
                bookPlans.add(plan)
            }
        }
        return ChapterCacheMigrator.ScanSummary(
            books.size,
            booksWithDir,
            deletedBookDirs,
            nrFlagFiles,
            bookPlans
        )
    }

}

/** 迁移执行进度（全局累计） */
data class LegacyMigrateProgress(
    val bookName: String,
    val done: Int,
    val total: Int,
    val renamed: Int,
    val skipped: Int,
    val failed: Int
)
