package io.legado.app.ui.book.manga

import io.legado.app.help.http.ImageRequestObserver
import io.legado.app.help.http.readObservedImageBytes
import io.legado.app.model.ImageCacheResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class MangaResponseTest {
    private val url = "https://fake.example/image?verify=fake-secret"
    private fun page(index: Int = 0, chapter: String = "chapter", book: String = "book",
                     source: String = "source", image: String = url) =
        MangaPageIdentity.create(book, source, chapter, index, image)
    private fun response(code: Int = 200, text: String = "hello", type: String = "text/plain", length: String? = null): Response =
        Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").header("Content-Type", type).header("Set-Cookie", "fake-private")
            .apply { if (length != null) header("Content-Length", length) }
            .body(text.toResponseBody(type.toMediaType())).build()

    @Test fun `text and HTML errors preserve actual HTTP statuses and body`() = runBlocking {
        for (code in listOf(200, 401, 403, 404, 429, 500)) for (html in listOf(false, true)) {
            val store = MangaPageDiagnostics()
            val observer = store.begin(page(), url)
            val body = if (html) "<html>permission denied</html>" else "hello"
            var calls = 0
            val result = readObservedImageBytes(observer) { calls++; response(code, body, if (html) "text/html" else "text/plain") }
            assertEquals(1, calls)
            assertEquals(body, result.toString(Charsets.UTF_8))
            val info = store.snapshot(page())!!
            assertTrue(info.hasResponse)
            assertEquals(code, info.status)
            assertEquals(body, info.body)
            assertEquals(result.size, info.actualBytes)
            assertNull(info.declaredLength)
            assertFalse(info.headers!!.contains("fake-private"))
            assertFalse(info.finalUrl!!.contains("fake-secret"))
        }
    }

    @Test fun `JSON nested authentication is redacted`() = runBlocking {
        val store = MangaPageDiagnostics()
        readObservedImageBytes(store.begin(page(), url)) {
            response(403, """{"error":"expired","nested":{"authorization":{"bearer":"private-value"},"verify":"private-sign"}}""", "application/json")
        }
        val text = MangaResponseFormatter.format(store.snapshot(page()))
        assertTrue(text.contains("expired"))
        assertFalse(text.contains("private-value"))
        assertFalse(text.contains("private-sign"))
    }

    @Test fun `image and unknown binary never become a textual preview`() = runBlocking {
        for (type in listOf("image/png", "application/octet-stream")) {
            val store = MangaPageDiagnostics()
            val bytes = byteArrayOf(0, 1, 2, 3, -1)
            val res = response(type = type).newBuilder().body(bytes.toResponseBody(type.toMediaType())).build()
            assertArrayEquals(bytes, readObservedImageBytes(store.begin(page(), url)) { res })
            assertTrue(store.snapshot(page())!!.body!!.contains("二进制"))
        }
    }

    @Test fun `no response failures record actual network stage`() = runBlocking {
        for ((error, stage) in listOf(UnknownHostException("fake dns") to "DNS",
            ConnectException("fake connect") to "连接", SSLException("fake TLS") to "TLS")) {
            val store = MangaPageDiagnostics()
            try { readObservedImageBytes(store.begin(page(), url)) { throw error }; fail() }
            catch (actual: Exception) { assertSame(error, actual) }
            val info = store.snapshot(page())!!
            assertFalse(info.hasResponse)
            assertEquals(stage, info.failureStage)
            assertNull(info.status)
            assertTrue(MangaResponseFormatter.format(info).contains("没有取得 HTTP Response"))
        }
    }

    @Test fun `body failure retains headers and unknown actual byte count`() = runBlocking {
        val store = MangaPageDiagnostics()
        var closed = false
        val broken = object : ResponseBody() {
            override fun contentType() = "text/html".toMediaType()
            override fun contentLength() = -1L
            private val stream = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("body failed")
                override fun timeout() = Timeout.NONE
                override fun close() { closed = true }
            }.buffer()
            override fun source(): BufferedSource = stream
        }
        try {
            readObservedImageBytes(store.begin(page(), url)) { response(429).newBuilder().body(broken).build() }
            fail()
        } catch (_: IOException) { }
        val info = store.snapshot(page())!!
        assertEquals(429, info.status)
        assertNotNull(info.headers)
        assertEquals("读取正文", info.failureStage)
        assertNull(info.actualBytes)
        assertTrue(closed)
    }

    @Test fun `sample and character limits do not truncate business bytes`() = runBlocking {
        val store = MangaPageDiagnostics()
        val data = "a".repeat(100_000)
        val bytes = readObservedImageBytes(store.begin(page(), url)) { response(text = data, length = "123456") }
        val info = store.snapshot(page())!!
        assertEquals(100_000, bytes.size)
        assertEquals(65_536, info.sampledBytes)
        assertEquals(32_000, info.body!!.length)
        assertEquals("123456", info.declaredLength)
        assertEquals(100_000, info.actualBytes)
        assertTrue(info.bodyTruncated)
    }

    @Test fun `observer disabled and observer exception keep response unchanged`() = runBlocking {
        assertEquals("hello", readObservedImageBytes(null) { response() }.toString(Charsets.UTF_8))
        val throwing = ImageRequestObserver { throw IllegalStateException("observer only") }
        assertEquals("hello", readObservedImageBytes(throwing) { response() }.toString(Charsets.UTF_8))
    }

    @Test fun `cancellation propagates without becoming network or cache failure`() = runBlocking {
        val store = MangaPageDiagnostics()
        val error = CancellationException("cancel")
        try { readObservedImageBytes(store.begin(page(), url)) { throw error }; fail() }
        catch (actual: CancellationException) { assertSame(error, actual) }
        val info = store.snapshot(page())!!
        assertEquals("任务取消", info.failureStage)
        assertNull(info.cache)
        assertTrue(info.finished)
    }

    @Test fun `observation callback cannot swallow cancellation`() = runBlocking {
        val cancel = CancellationException("cancel")
        try {
            readObservedImageBytes(ImageRequestObserver { throw cancel }) { response() }
            fail()
        } catch (actual: CancellationException) { assertSame(cancel, actual) }
    }

    @Test fun `declared charset and missing Content Type preserve readable bounded text`() = runBlocking {
        val store = MangaPageDiagnostics()
        val type = "text/plain; charset=GBK".toMediaType()
        val bytes = "验证失败".toByteArray(charset("GBK"))
        readObservedImageBytes(store.begin(page(), url)) {
            response().newBuilder().header("Content-Type", type.toString()).body(bytes.toResponseBody(type)).build()
        }
        assertEquals("验证失败", store.snapshot(page())!!.body)
        readObservedImageBytes(store.begin(page(), url)) { response().newBuilder().removeHeader("Content-Type").build() }
        assertNull(store.snapshot(page())!!.contentType)
        assertEquals("hello", store.snapshot(page())!!.body)
    }

    @Test fun `HTTP success does not mean decoding or persistent cache success`() = runBlocking {
        val store = MangaPageDiagnostics()
        val observer = store.begin(page(), url)
        readObservedImageBytes(observer) { response(text = "<html>error</html>") }
        observer.decrypted(false, 18)
        observer.validated(false)
        observer.cached(ImageCacheResult.FAILED)
        observer.displayFailed()
        val info = store.snapshot(page())!!
        assertEquals(200, info.status)
        assertTrue(info.decrypt!!.contains("未配置"))
        assertEquals("图片解码/校验", info.failureStage)
        assertEquals(ImageCacheResult.FAILED, info.cache)
        assertTrue(info.finished)
    }

    @Test fun `decryption null empty and valid are distinguished without invented exception`() {
        val store = MangaPageDiagnostics()
        for (size in listOf(null, 0, 10)) {
            store.begin(page(), url).decrypted(true, size)
            val text = store.snapshot(page())!!.decrypt!!
            assertTrue(text.contains(when(size) { null -> "具体异常不可得"; 0 -> "空数据"; else -> "10 字节" }))
        }
    }

    @Test fun `cache HIT gets a new empty network record after retry`() = runBlocking {
        val store = MangaPageDiagnostics()
        readObservedImageBytes(store.begin(page(), url)) { response(text = "old response") }
        val observer = store.begin(page(), url)
        observer.cached(ImageCacheResult.HIT)
        observer.displayed()
        val info = store.snapshot(page())!!
        assertFalse(info.hasResponse)
        assertNull(info.status)
        assertNull(info.body)
        val text = MangaResponseFormatter.format(info)
        assertTrue(text.contains("本次使用已有缓存，没有发起新的 HTTP 请求"))
        assertFalse(text.contains("old response"))
    }

    @Test fun `STORED and FAILED stay distinct from display success`() {
        for (cache in listOf(ImageCacheResult.STORED, ImageCacheResult.FAILED)) {
            val store = MangaPageDiagnostics()
            val observer = store.begin(page(), url)
            observer.validated(true)
            observer.cached(cache)
            observer.displayed()
            assertEquals(cache, store.snapshot(page())!!.cache)
            assertEquals("图片最终显示成功", store.snapshot(page())!!.display)
        }
    }

    @Test fun `data URI never claims HTTP Response`() {
        val store = MangaPageDiagnostics()
        val observer = store.begin(page(), "data:image/png;base64,fake-private")
        observer.dataUri(12)
        val text = MangaResponseFormatter.format(store.snapshot(page()))
        assertTrue(text.contains("data URI：没有 HTTP Response"))
        assertFalse(text.contains("fake-private"))
    }

    @Test fun `same page retry drops late success failure and cancellation`() {
        val store = MangaPageDiagnostics()
        val old = store.begin(page(), url)
        val id = store.requestId(page())
        val current = store.begin(page(), url)
        assertNotEquals(id, store.requestId(page()))
        current.cached(ImageCacheResult.STORED)
        val before = store.snapshot(page())
        old.displayed(); old.failure("old", IOException("old")); old.cancelled()
        assertEquals(before, store.snapshot(page()))
    }

    @Test fun `page chapter book source and full signed URL identities do not alias`() {
        val store = MangaPageDiagnostics()
        store.begin(page(), url).displayed()
        for (different in listOf(page(index = 1), page(chapter = "chapter2"), page(book = "book2"),
            page(source = "source2"), page(image = "$url-new"))) assertNull(store.snapshot(different))
        val old = store.begin(page(), url)
        store.begin(page(source = "new"), url)
        old.displayed()
        assertNull(store.snapshot(page()))
    }

    @Test fun `cleared and destroyed sessions cannot be revived by late callbacks`() {
        val store = MangaPageDiagnostics()
        val old = store.begin(page(), url)
        store.clear()
        store.begin(page(), url)
        old.displayed()
        assertNull(store.snapshot(page())!!.display)
        store.close()
        old.failure("late", IOException())
        store.begin(page(), url).displayed()
        assertEquals(0, store.size())
    }

    @Test fun `missing and pending records are explicit`() {
        val store = MangaPageDiagnostics()
        assertEquals("当前页面暂无请求记录", MangaResponseFormatter.format(store.snapshot(page())))
        store.begin(page(), url)
        assertTrue(MangaResponseFormatter.format(store.snapshot(page())).contains("请求仍在进行中"))
    }

    @Test fun `eight record limit evicts old callback destination`() {
        val store = MangaPageDiagnostics()
        val old = store.begin(page(), url)
        (1..8).forEach { store.begin(page(index = it), url) }
        assertEquals(8, store.size())
        old.displayed()
        assertNull(store.snapshot(page()))
    }

    @Test fun `total character budget evicts previews without retaining body in handles`() {
        val store = MangaPageDiagnostics(maxChars = 40_000)
        val first = store.begin(page(), url)
        first.body("a".repeat(32_000).toByteArray())
        store.begin(page(index = 1), url).body("b".repeat(32_000).toByteArray())
        assertTrue(store.retainedChars() <= 40_000)
        assertEquals(1, store.size())
        first.body("c".repeat(32_000).toByteArray())
        assertEquals(1, store.size())
    }

    @Test fun `fallback shows observation boundary and final display outcome`() {
        val store = MangaPageDiagnostics()
        val observer = store.begin(page(), url)
        observer.cached(ImageCacheResult.HIT)
        observer.displayFailed(fallback = true)
        assertFalse(store.snapshot(page())!!.finished)
        observer.displayed()
        assertTrue(store.snapshot(page())!!.display!!.contains("兜底最终显示成功"))
        assertFalse(store.snapshot(page())!!.hasResponse)
    }
}
