package io.legado.app.help.http

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [newCallResponse] 重试循环的响应释放行为回归：
 * - 重试过程中被丢弃的中间非 2xx 响应必须被关闭（修复点：原先直接覆盖泄漏连接）；
 * - 最终返回给调用方的响应（成功或重试耗尽）保持未关闭，关闭责任在调用方；
 * - 重试次数、重试条件、返回值语义与修复前一致。
 *
 * 测试用本机 ServerSocket 模拟 HTTP 服务（无新增依赖），
 * 通过拦截器把响应体换成可跟踪 close 的包装体来观察释放行为。
 */
class OkHttpRetryCloseTest {

    /** 记录 close 调用的 ResponseBody 包装体 */
    private class TrackingBody(private val delegate: ResponseBody) : ResponseBody() {
        @Volatile
        var closed = false
            private set

        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun source(): BufferedSource = delegate.source()

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** 逐连接返回预设响应的迷你 HTTP 服务；Connection: close 保证每个请求一个新连接 */
    private class MiniServer(private val statusCodes: List<Int>, private val bodyText: String) {
        private lateinit var serverSocket: ServerSocket
        private val requestThread = Thread(this::serve)
        val requestCount = AtomicInteger(0)
        val trackedBodies = Collections.synchronizedList(mutableListOf<TrackingBody>())
        @Volatile
        private var stopped = false

        fun start() {
            serverSocket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
            requestThread.isDaemon = true
            requestThread.start()
        }

        val url: String get() = "http://127.0.0.1:${serverSocket.localPort}/test"

        private fun serve() {
            var servedIndex = 0
            while (!stopped) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    return
                }
                try {
                    socket.use { handle(socket, servedIndex++) }
                } catch (e: Exception) {
                    // 单连接级别异常不中断服务，与真实服务器行为一致
                }
            }
        }

        private fun handle(socket: Socket, index: Int) {
            readRequestHead(socket.getInputStream())
            val statusCode = statusCodes[statusCodes.indices.last.coerceAtMost(index)]
            requestCount.incrementAndGet()
            val bodyBytes = bodyText.toByteArray()
            val output = socket.getOutputStream()
            output.write(
                ("HTTP/1.1 $statusCode Test\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
            )
            output.write(bodyBytes)
            output.flush()
        }

        private fun readRequestHead(input: InputStream) {
            var a = -1
            var b = -1
            var c = -1
            var d = -1
            while (true) {
                d = c
                c = b
                b = a
                a = input.read()
                if (a < 0) return
                if (a == '\n'.code && b == '\r'.code && c == '\n'.code && d == '\r'.code) return
            }
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket.close() }
        }
    }

    private fun MiniServer.newClient(): OkHttpClient {
        val server = this
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val tracked = TrackingBody(response.body)
                server.trackedBodies.add(tracked)
                response.newBuilder().body(tracked).build()
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Test
    fun 重试时中间失败响应被关闭_最终成功响应归调用方() {
        val server = MiniServer(listOf(500, 200), "retry-ok-body")
        server.start()
        try {
            val response = runBlocking {
                server.newClient().newCallResponse(retry = 1) {
                    url(server.url)
                }
            }
            assertEquals(2, server.requestCount.get())
            assertEquals(2, server.trackedBodies.size)
            assertTrue("第一次失败的中间响应应已被关闭", server.trackedBodies[0].closed)
            assertFalse("最终成功响应的关闭责任仍在调用方", server.trackedBodies[1].closed)
            assertEquals(200, response.code)
            assertEquals("retry-ok-body", response.body.string())
            response.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun 重试耗尽返回最后一次失败响应且不提前关闭() {
        val server = MiniServer(listOf(500, 500, 500), "all-fail-body")
        server.start()
        try {
            val response = runBlocking {
                server.newClient().newCallResponse(retry = 2) {
                    url(server.url)
                }
            }
            assertEquals(3, server.requestCount.get())
            assertEquals(3, server.trackedBodies.size)
            assertTrue(server.trackedBodies[0].closed)
            assertTrue(server.trackedBodies[1].closed)
            assertFalse("最后一次失败响应仍返回给调用方，不得提前关闭", server.trackedBodies[2].closed)
            assertEquals(500, response.code)
            assertEquals("all-fail-body", response.body.string())
            response.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun 首次成功不发生重试_无中间关闭() {
        val server = MiniServer(listOf(200), "first-ok-body")
        server.start()
        try {
            val response = runBlocking {
                server.newClient().newCallResponse(retry = 3) {
                    url(server.url)
                }
            }
            assertEquals("首次成功不得重试", 1, server.requestCount.get())
            assertEquals(1, server.trackedBodies.size)
            assertFalse(server.trackedBodies[0].closed)
            assertEquals("first-ok-body", response.body.string())
            response.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun 默认无重试时失败响应归调用方关闭() {
        val server = MiniServer(listOf(500), "no-retry-body")
        server.start()
        try {
            val response = runBlocking {
                server.newClient().newCallResponse {
                    url(server.url)
                }
            }
            assertEquals(1, server.requestCount.get())
            assertEquals(1, server.trackedBodies.size)
            assertFalse(server.trackedBodies[0].closed)
            assertEquals("no-retry-body", response.body.string())
            response.close()
        } finally {
            server.stop()
        }
    }
}
