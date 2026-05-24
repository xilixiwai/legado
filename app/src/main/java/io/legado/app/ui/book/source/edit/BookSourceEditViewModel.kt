package io.legado.app.ui.book.source.edit

import android.app.Application
import android.content.Intent
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.RuleComplete
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.storage.ImportOldData
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.jsonPath
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers


class BookSourceEditViewModel(application: Application) : BaseViewModel(application) {
    var autoComplete = false
    var bookSource: BookSource? = null

    fun initData(intent: Intent, onFinally: () -> Unit) {
        execute {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            var source: BookSource? = null
            if (sourceUrl != null) {
                source = appDb.bookSourceDao.getBookSource(sourceUrl)
            }
            source?.let {
                bookSource = it
            }
        }.onFinally {
            onFinally()
        }
    }

    fun save(source: BookSource, success: ((BookSource) -> Unit)? = null) {
        execute {
            if (source.bookSourceUrl.isBlank() || source.bookSourceName.isBlank()) {
                throw NoStackTraceException(context.getString(R.string.non_null_name_url))
            }

            // 备份旧URL，用于后续书籍数据同步
            val oldUrl = bookSource?.bookSourceUrl

            val oldSource = bookSource ?: BookSource()
            val urlChanged = oldUrl != null && oldUrl != source.bookSourceUrl

            if (!source.equal(oldSource)) {
                source.lastUpdateTime = System.currentTimeMillis()
                if (oldSource.exploreUrl != source.exploreUrl) {
                    oldSource.clearExploreKindsCache()
                }
                if (oldSource.jsLib != source.jsLib) {
                    SharedJsScope.remove(oldSource.jsLib)
                }
            }

            // 换域名前检查新 URL 是否已被其他书源占用
            if (urlChanged && appDb.bookSourceDao.has(source.bookSourceUrl)) {
                throw NoStackTraceException(
                    "目标书源 URL 已存在：${source.bookSourceUrl}"
                )
            }

            val tag = "BookSourceMigration"
            android.util.Log.i(
                tag,
                "migrate source: $oldUrl -> ${source.bookSourceUrl}"
            )

            // 迁移前捕获受影响的书籍，用于事务后迁移缓存目录
            val oldBooks = if (urlChanged) appDb.bookDao.getByOrigin(oldUrl) else emptyList()

            // 所有 DB 操作在一个事务内，确保原子性
            var lastStep = 0
            try {
                appDb.runInTransaction {
                    if (urlChanged) {
                        // 延后 FK 检查到事务提交时，否则更新 books.bookUrl（主键）时
                        // SQLite 会立即检查 chapters 外键，但 chapters 尚未更新 → FK 787
                        appDb.openHelper.writableDatabase
                            .execSQL("PRAGMA defer_foreign_keys = ON")

                        // 书源换域名时的操作顺序：
                        // 1 新书源（ABORT 策略，纯 INSERT，不触发 REPLACE 的 DELETE 行为）
                        appDb.bookSourceDao.insertOrAbort(source); lastStep = 1
                        // 2 同步更新 chapters.bookUrl（FK: chapters.bookUrl → books.bookUrl）
                        val chapterCount = appDb.bookChapterDao.replaceChapterUrls(oldUrl, source.bookSourceUrl); lastStep = 2
                        android.util.Log.i(tag, "migrated $chapterCount chapters bookUrl from $oldUrl")
                        // 3 替换 books.bookUrl + tocUrl（带上 WHERE origin=oldUrl 限定范围）
                        val bookCount = appDb.bookDao.replaceBookUrls(oldUrl, source.bookSourceUrl); lastStep = 3
                        android.util.Log.i(tag, "migrated $bookCount books bookUrl, tocUrl from $oldUrl")
                        // 4 更新 books.origin
                        appDb.bookDao.updateBookSourceUrl(oldUrl, source.bookSourceUrl); lastStep = 4
                        // 5 更新 searchBooks 的 origin（避免后续 FK 级联删除搜索缓存）
                        appDb.searchBookDao.updateOrigin(oldUrl, source.bookSourceUrl); lastStep = 5
                        // 6 删除旧书源（searchBooks 已指向新 URL，不会触发级联）
                        appDb.bookSourceDao.delete(oldUrl); lastStep = 6
                        // 7 清理旧书源的数据库缓存
                        appDb.cacheDao.deleteSourceVariables(oldUrl); lastStep = 7
                    } else {
                        bookSource?.let {
                            appDb.bookSourceDao.delete(it)
                        }
                        appDb.bookSourceDao.insert(source)
                    }
                }
            } catch (e: Exception) {
                throw NoStackTraceException(
                    "step${lastStep + 1} failed[$oldUrl->${source.bookSourceUrl}]: ${e.message}"
                )
            }

            bookSource = source

            // 移除 SharedPreferences 中旧书源的配置（不分分支，始终清理）
            if (oldUrl != null) {
                SourceConfig.removeSource(oldUrl)
            }

            // 迁移书籍缓存目录（folderName = MD5(bookUrl)，域名变化后需要重命名目录）
            if (urlChanged) {
                oldBooks.forEach { oldBook ->
                    val newBookUrl = source.bookSourceUrl + oldBook.bookUrl.removePrefix(oldUrl)
                    appDb.bookDao.getBook(newBookUrl)?.let { newBook ->
                        BookHelp.updateCacheFolder(oldBook, newBook)
                        android.util.Log.i(tag, "moved cache: ${oldBook.bookUrl} -> $newBookUrl")
                    }
                }
            }

            source

        }.onSuccess {
            success?.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage)
            it.printOnDebug()
        }
    }

    fun pasteSource(onSuccess: (source: BookSource) -> Unit) {
        execute(context = Dispatchers.Main) {
            val text = context.getClipText()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            } else {
                importSource(text, onSuccess)
            }
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }

    fun importSource(text: String, finally: (source: BookSource) -> Unit) {
        execute {
            importSource(text)
        }.onSuccess {
            finally.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }

    suspend fun importSource(text: String): BookSource {
        return when {
            text.isAbsUrl() -> {
                val text1 = okHttpClient.newCallStrResponse { url(text) }.body
                importSource(text1!!)
            }

            text.isJsonArray() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    val items: List<Map<String, Any>> = jsonPath.parse(text).read("$")
                    val jsonItem = jsonPath.parse(items[0])
                    ImportOldData.fromOldBookSource(jsonItem)
                } else {
                    GSON.fromJsonArray<BookSource>(text).getOrThrow()[0]
                }
            }

            text.isJsonObject() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    val jsonItem = jsonPath.parse(text)
                    ImportOldData.fromOldBookSource(jsonItem)
                } else {
                    GSON.fromJsonObject<BookSource>(text).getOrThrow()
                }
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    fun clearCookie(url: String) {
        execute {
            CookieStore.removeCookie(url)
        }
    }

    fun ruleComplete(rule: String?, preRule: String? = null, type: Int = 1): String? {
        if (autoComplete) {
            return RuleComplete.autoComplete(rule, preRule, type)
        }
        return rule
    }

}