package io.legado.app.model.webBook

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.addType
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.getBookType
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.DiagnosticUtils
import io.legado.app.utils.ExploreDebugRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Headers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object WebBook {

    /**
     * 搜索
     */
    fun searchBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
    ): Coroutine<ArrayList<SearchBook>> {
        return Coroutine.async(scope, context, start = start, executeContext = executeContext) {
            searchBookAwait(bookSource, key, page)
        }
    }

    suspend fun searchBookAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null
    ): ArrayList<SearchBook> {
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) {
            throw NoStackTraceException("搜索url不能为空")
        }
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchUrl,
            key = key,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        //检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = true,
            isRedirect = res.raw.priorResponse?.isRedirect == true,
            filter = filter,
            shouldBreak = shouldBreak
        )
    }

    /**
     * 发现
     */
    fun exploreBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        exploreName: String = "",
        debugCallback: ((ExploreDebugInfo) -> Unit)? = null,
    ): Coroutine<List<SearchBook>> {
        return Coroutine.async(scope, context) {
            exploreBookAwait(bookSource, url, page, exploreName, debugCallback)
        }
    }

    suspend fun exploreBookAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
        exploreName: String = "",
        debugCallback: ((ExploreDebugInfo) -> Unit)? = null,
    ): ArrayList<SearchBook> {
        val ruleData = RuleData()
        val analyzeUrl = try {
            AnalyzeUrl(
                mUrl = url,
                page = page,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = ruleData,
                coroutineContext = coroutineContext
            )
        } catch (e: Throwable) {
            debugCallback?.let { callback ->
                runCatching {
                    callback(
                        ExploreDebugInfo(
                            sourceName = bookSource.bookSourceName,
                            sourceUrl = ExploreDebugRedactor.redactUrl(bookSource.bookSourceUrl),
                            exploreName = exploreName,
                            page = page ?: 1,
                            ruleUrl = ExploreDebugRedactor.redactText(
                                ExploreDebugRedactor.redactUrl(url)
                            ),
                            requestUrl = ExploreDebugRedactor.redactUrl(url),
                            errorType = DiagnosticUtils.exceptionType(e),
                            errorMessage = ExploreDebugRedactor.redactText(
                                e.localizedMessage ?: e.javaClass.simpleName
                            ),
                            ruleUsed = exploreRuleText(bookSource)
                        )
                    )
                }
            }
            throw e
        }
        var response: StrResponse? = null
        var parseStats: ExploreParseStats? = null
        var requestElapsedMs = 0L
        var parseElapsedMs = 0L
        try {
            val requestStart = System.currentTimeMillis()
            var res = analyzeUrl.getStrResponseAwait()
            requestElapsedMs = DiagnosticUtils.elapsedMs(requestStart)
            response = res
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                    response = res
                }
            }
            checkRedirect(bookSource, res)
            val parseStart = System.currentTimeMillis()
            val books = BookList.analyzeBookList(
                bookSource = bookSource,
                ruleData = ruleData,
                analyzeUrl = analyzeUrl,
                baseUrl = res.url,
                body = res.body,
                isSearch = false,
                parseCallback = { parseStats = it }
            )
            parseElapsedMs = DiagnosticUtils.elapsedMs(parseStart)
            debugCallback?.let { callback ->
                runCatching {
                    callback(
                        buildExploreDebugInfo(
                            bookSource = bookSource,
                            exploreName = exploreName,
                            page = page,
                            analyzeUrl = analyzeUrl,
                            response = response,
                            requestElapsedMs = requestElapsedMs,
                            parseElapsedMs = parseElapsedMs,
                            parseStats = parseStats,
                            books = books,
                            error = null
                        )
                    )
                }
            }
            return books
        } catch (e: Throwable) {
            debugCallback?.let { callback ->
                runCatching {
                    callback(
                        buildExploreDebugInfo(
                            bookSource = bookSource,
                            exploreName = exploreName,
                            page = page,
                            analyzeUrl = analyzeUrl,
                            response = response,
                            requestElapsedMs = requestElapsedMs,
                            parseElapsedMs = parseElapsedMs,
                            parseStats = parseStats,
                            books = null,
                            error = e
                        )
                    )
                }
            }
            throw e
        }
    }

    private fun buildExploreDebugInfo(
        bookSource: BookSource,
        exploreName: String,
        page: Int?,
        analyzeUrl: AnalyzeUrl,
        response: StrResponse?,
        requestElapsedMs: Long,
        parseElapsedMs: Long,
        parseStats: ExploreParseStats?,
        books: List<SearchBook>?,
        error: Throwable?,
    ): ExploreDebugInfo {
        val responseHeaders = response?.headers().orEmptyPairs()
        val networkRequest = response?.raw?.networkResponse?.request
        val rawRequest = networkRequest ?: response?.raw?.request
        val requestHeaders = rawRequest?.headers?.toPairs()?.takeIf { it.isNotEmpty() }
            ?: analyzeUrl.headerMap.map { it.key to it.value }
        val rawRequestUrl = analyzeUrl.url.ifBlank { analyzeUrl.ruleUrl }
        val rawFinalUrl = response?.url ?: ""
        val redirected = response?.raw?.let { raw ->
            generateSequence(raw.priorResponse) { it.priorResponse }.any { it.isRedirect }
        } == true || (rawRequestUrl.isNotBlank() && rawFinalUrl.isNotBlank() &&
            rawRequestUrl != rawFinalUrl)
        val preview = ExploreDebugRedactor.previewBody(response?.body)
        val cookieSent = requestHeaders.any { (name, value) ->
            name.equals("Cookie", true) && value.isNotBlank()
        }
        val setCookieReceived = response?.raw?.let { raw ->
            generateSequence(raw) { it.priorResponse }.any { responseInChain ->
                responseInChain.headers.values("Set-Cookie").any { it.isNotBlank() }
            }
        } == true
        return ExploreDebugInfo(
            sourceName = bookSource.bookSourceName,
            sourceUrl = ExploreDebugRedactor.redactUrl(bookSource.bookSourceUrl),
            exploreName = exploreName,
            page = page ?: 1,
            ruleUrl = ExploreDebugRedactor.redactText(
                ExploreDebugRedactor.redactUrl(analyzeUrl.ruleUrl)
            ),
            requestUrl = ExploreDebugRedactor.redactUrl(rawRequestUrl),
            finalUrl = ExploreDebugRedactor.redactUrl(rawFinalUrl),
            method = analyzeUrl.requestMethodName,
            requestMode = analyzeUrl.requestModeName,
            statusCode = response?.code() ?: 0,
            statusMessage = ExploreDebugRedactor.redactText(response?.message() ?: ""),
            requestElapsedMs = requestElapsedMs,
            parseElapsedMs = parseElapsedMs,
            redirected = redirected,
            requestHeaders = ExploreDebugRedactor.redactHeaders(requestHeaders),
            responseHeaders = ExploreDebugRedactor.redactHeaders(responseHeaders),
            cookieSent = cookieSent,
            cookieJarEnabled = bookSource.enabledCookieJar == true,
            setCookieReceived = setCookieReceived,
            contentType = response?.headers()?.get("Content-Type")
                ?: if (analyzeUrl.requestModeName == "WebView") "WebView 未提供响应头" else "",
            body = preview.text,
            bodyLength = preview.originalLength,
            bodyTruncated = preview.truncated,
            errorType = error?.let { DiagnosticUtils.exceptionType(it) },
            errorMessage = error?.let {
                ExploreDebugRedactor.redactText(it.localizedMessage ?: it.javaClass.simpleName)
            },
            ruleUsed = parseStats?.rule ?: exploreRuleText(bookSource),
            collectionCount = parseStats?.collectionCount ?: -1,
            resultCount = parseStats?.resultCount ?: books?.size ?: 0,
            resultPreview = exploreResultPreview(books)
        )
    }

    private fun exploreRuleText(bookSource: BookSource): String {
        val exploreRule = bookSource.getExploreRule()
        return if (exploreRule.bookList.isNullOrBlank()) {
            bookSource.getSearchRule().bookList.orEmpty()
        } else {
            exploreRule.bookList.orEmpty()
        }
    }

    private fun exploreResultPreview(books: List<SearchBook>?): String {
        if (books.isNullOrEmpty()) return ""
        return books.take(5).joinToString("\n") {
            "${it.name} / ${it.author} / ${ExploreDebugRedactor.redactUrl(it.bookUrl)}"
        }.take(1000)
    }

    private fun Headers.toPairs(): List<Pair<String, String>> {
        return names().flatMap { name -> values(name).map { name to it } }
    }

    private fun Headers?.orEmptyPairs(): List<Pair<String, String>> {
        return this?.toPairs().orEmpty()
    }

    /**
     * 书籍信息
     */
    fun getBookInfo(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        context: CoroutineContext = Dispatchers.IO,
        canReName: Boolean = true,
    ): Coroutine<Book> {
        return Coroutine.async(scope, context) {
            getBookInfoAwait(bookSource, book, canReName)
        }
    }

    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = book.bookUrl,
                body = book.infoHtml,
                canReName = canReName
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.bookUrl,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = book,
                coroutineContext = coroutineContext
            )
            var res = analyzeUrl.getStrResponseAwait()
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                }
            }
            checkRedirect(bookSource, res)
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = res.url,
                body = res.body,
                canReName = canReName
            )
        }
        return book
    }

    /**
     * 目录
     */
    fun getChapterList(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        context: CoroutineContext = Dispatchers.IO
    ): Coroutine<List<BookChapter>> {
        return Coroutine.async(scope, context) {
            getChapterListAwait(bookSource, book, runPerJs).getOrThrow()
        }
    }

    suspend fun runPreUpdateJs(bookSource: BookSource, book: Book): Result<Unit> {
        return kotlin.runCatching {
            val preUpdateJs = bookSource.ruleToc?.preUpdateJs
            if (!preUpdateJs.isNullOrBlank()) {
                AnalyzeRule(book, bookSource, true)
                    .setCoroutineContext(coroutineContext)
                    .evalJS(preUpdateJs)
            }
        }.onFailure {
            coroutineContext.ensureActive()
            AppLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false
    ): Result<List<BookChapter>> {
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        return kotlin.runCatching {
            if (runPerJs) {
                runPreUpdateJs(bookSource, book).getOrThrow()
            }
            if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = book.tocUrl,
                    body = book.tocHtml
                )
            } else {
                val analyzeUrl = AnalyzeUrl(
                    mUrl = book.tocUrl,
                    baseUrl = book.bookUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = coroutineContext
                )
                var res = analyzeUrl.getStrResponseAwait()
                //检测书源是否已登录
                bookSource.loginCheckJs?.let { checkJs ->
                    if (checkJs.isNotBlank()) {
                        res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                    }
                }
                checkRedirect(bookSource, res)
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = res.url,
                    body = res.body
                )
            }
        }.onFailure {
            coroutineContext.ensureActive()
        }
    }

    /**
     * 章节内容
     */
    fun getContent(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
    ): Coroutine<String> {
        return Coroutine.async(
            scope,
            context,
            start = start,
            executeContext = executeContext,
            semaphore = semaphore
        ) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, needSave)
        }
    }

    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true
    ): String {
        if (bookSource.getContentRule().content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
        return if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = bookChapter.getAbsoluteURL(),
                body = book.tocHtml,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = bookChapter.getAbsoluteURL(),
                baseUrl = book.tocUrl,
                source = bookSource,
                ruleData = book,
                chapter = bookChapter,
                coroutineContext = coroutineContext
            )
            var res = analyzeUrl.getStrResponseAwait(
                jsStr = bookSource.getContentRule().webJs,
                sourceRegex = bookSource.getContentRule().sourceRegex
            )
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                }
            }
            checkRedirect(bookSource, res)
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = res.url,
                body = res.body,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        }
    }

    /**
     * 精准搜索
     */
    fun preciseSearch(
        scope: CoroutineScope,
        bookSourceParts: List<BookSourcePart>,
        name: String,
        author: String,
        context: CoroutineContext = Dispatchers.IO,
        semaphore: Semaphore? = null,
    ): Coroutine<Pair<Book, BookSource>> {
        return Coroutine.async(scope, context, semaphore = semaphore) {
            for (s in bookSourceParts) {
                val source = s.getBookSource() ?: continue
                val book = preciseSearchAwait(source, name, author).getOrNull()
                if (book != null) {
                    return@async Pair(book, source)
                }
            }
            throw NoStackTraceException("没有搜索到<$name>$author")
        }
    }

    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): Result<Book> {
        return kotlin.runCatching {
            coroutineContext.ensureActive()
            searchBookAwait(
                bookSource, name,
                filter = { fName, fAuthor -> fName == name && fAuthor == author },
                shouldBreak = { it > 0 }
            ).firstOrNull()?.let { searchBook ->
                coroutineContext.ensureActive()
                return@runCatching searchBook.toBook()
            }
            throw NoStackTraceException("未搜索到 $name($author) 书籍")
        }.onFailure {
            coroutineContext.ensureActive()
        }
    }

    /**
     * 检测重定向
     */
    private fun checkRedirect(bookSource: BookSource, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                Debug.log(bookSource.bookSourceUrl, "≡检测到重定向(${it.code})")
                Debug.log(bookSource.bookSourceUrl, "┌重定向后地址")
                Debug.log(bookSource.bookSourceUrl, "└${response.url}")
            }
        }
    }

}