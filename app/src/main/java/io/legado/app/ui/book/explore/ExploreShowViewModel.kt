package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.ExploreDebugInfo
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.DiagnosticUtils
import io.legado.app.utils.ExploreDebugRedactor
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap


@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    val booksData = MutableLiveData<List<SearchBook>>()
    val errorLiveData = MutableLiveData<String>()
    val debugInfoLiveData = MutableLiveData<ExploreDebugInfo?>()
    private var bookSource: BookSource? = null
    private var exploreUrl: String? = null
    private var exploreName: String = ""
    private var sourceUrl: String? = null
    @Volatile
    private var lastDebugInfo: ExploreDebugInfo? = null
    private var page = 1
    private var books = linkedSetOf<SearchBook>()

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                    }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun initData(intent: Intent) {
        execute {
            sourceUrl = intent.getStringExtra("sourceUrl")
            exploreUrl = intent.getStringExtra("exploreUrl")
            exploreName = intent.getStringExtra("exploreName") ?: ""
            if (bookSource == null && sourceUrl != null) {
                bookSource = appDb.bookSourceDao.getBookSource(sourceUrl!!)
            }
            explore()
        }
    }

    fun explore() {
        val source = bookSource
        val url = exploreUrl
        if (source == null || url.isNullOrBlank()) {
            val reason = if (source == null) {
                context.getString(R.string.error_no_source)
            } else {
                "发现地址为空"
            }
            AppLog.put(
                "[诊断]发现未发起 source=${DiagnosticUtils.sanitizeUrl(sourceUrl)} " +
                    "url=${ExploreDebugRedactor.redactUrl(url)} reason=$reason"
            )
            errorLiveData.postValue(reason)
            debugInfoLiveData.postValue(
                ExploreDebugInfo(
                    sourceName = source?.bookSourceName ?: "",
                    sourceUrl = ExploreDebugRedactor.redactUrl(
                        source?.bookSourceUrl ?: sourceUrl ?: ""
                    ),
                    exploreName = exploreName,
                    requestUrl = ExploreDebugRedactor.redactUrl(url),
                    errorType = "NoRequest",
                    errorMessage = reason
                )
            )
            return
        }
        // [诊断] 发现链路:仅记录来源/请求 URL(脱敏)/耗时/异常类型,不改变任何请求行为
        val startTime = System.currentTimeMillis()
        AppLog.put(
            "[诊断]发现开始 source=${source.bookSourceUrl} page=$page " +
                "url=${DiagnosticUtils.sanitizeUrl(url)}"
        )
        WebBook.exploreBook(
            viewModelScope, source, url, page,
            exploreName = exploreName,
            debugCallback = { debugInfo ->
                lastDebugInfo = debugInfo
                debugInfoLiveData.postValue(debugInfo)
            }
        )
            .timeout(if (BuildConfig.DEBUG) 0L else 30000L)
            .onSuccess(IO) { searchBooks ->
                books.addAll(searchBooks)
                booksData.postValue(books.toList())
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                page++
                val debugInfo = lastDebugInfo
                AppLog.put(
                    "[诊断]发现成功 source=${source.bookSourceUrl} count=${searchBooks.size} " +
                        "status=${debugInfo?.statusCode} type=${debugInfo?.contentType} " +
                        "bodyLen=${debugInfo?.bodyLength} parsed=${debugInfo?.resultCount} " +
                        "耗时=${DiagnosticUtils.elapsedMs(startTime)}ms"
                )
            }.onError {
                it.printOnDebug()
                errorLiveData.postValue(it.stackTraceStr)
                val debugInfo = lastDebugInfo
                AppLog.put(
                    "[诊断]发现失败 source=${source.bookSourceUrl} " +
                        "耗时=${DiagnosticUtils.elapsedMs(startTime)}ms " +
                        "timeout=${DiagnosticUtils.isTimeout(it)} " +
                        "status=${debugInfo?.statusCode} type=${debugInfo?.contentType} " +
                        "bodyLen=${debugInfo?.bodyLength} " +
                        "异常=${DiagnosticUtils.exceptionType(it)} ${it.localizedMessage}",
                    it
                )
            }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

}
