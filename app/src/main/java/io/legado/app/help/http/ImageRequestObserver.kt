package io.legado.app.help.http

import io.legado.app.model.ImageCacheResult
import io.legado.app.utils.MangaDebugRedactor
import kotlinx.coroutines.CancellationException
import okhttp3.Response
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.charset.Charset
import javax.net.ssl.SSLException

/** 只保存有限、已脱敏的值；不持有网络对象、正文 bytes 或 Android 对象。 */
data class ImageRequestSnapshot(
    val inputUrl: String,
    val startedAt: Long,
    val requestUrl: String? = null,
    val finalUrl: String? = null,
    val requestAt: Long? = null,
    val hasResponse: Boolean = false,
    val status: Int? = null,
    val headers: String? = null,
    val contentType: String? = null,
    val declaredLength: String? = null,
    val actualBytes: Int? = null,
    val sampledBytes: Int? = null,
    val body: String? = null,
    val bodyTruncated: Boolean = false,
    val nonHttp: Boolean = false,
    val decrypt: String? = null,
    val validation: String? = null,
    val cache: ImageCacheResult? = null,
    val display: String? = null,
    val failureStage: String? = null,
    val error: String? = null,
    val finished: Boolean = false,
    val fallback: Boolean = false,
) {
    val retainedChars: Int get() = listOf(inputUrl, requestUrl, finalUrl, headers, contentType,
        declaredLength, body, decrypt, validation, display, failureStage, error).sumOf { it?.length ?: 0 }
}

/** 每个请求一个句柄，只引用有限存储的更新入口；被逐出/作废的请求不会继续保留快照。 */
class ImageRequestObserver(private val update: ((ImageRequestSnapshot) -> ImageRequestSnapshot) -> Unit) {
    private fun record(edit: (ImageRequestSnapshot) -> ImageRequestSnapshot) {
        // 普通观察异常不得影响业务；取消始终沿原调用链传播。
        try {
            update(edit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    fun request(url: String) = record {
        it.copy(requestUrl = MangaDebugRedactor.url(url), requestAt = System.currentTimeMillis())
    }

    fun response(response: Response) = record {
        it.copy(hasResponse = true, status = response.code,
            finalUrl = MangaDebugRedactor.url(response.request.url.toString()),
            headers = MangaDebugRedactor.headers(response.headers.toList()),
            contentType = response.header("Content-Type")?.let { v -> MangaDebugRedactor.text(v, 256) },
            declaredLength = response.header("Content-Length")?.let { v -> MangaDebugRedactor.text(v, 128) })
    }

    fun body(bytes: ByteArray, charset: Charset = Charsets.UTF_8) = record {
        val count = minOf(bytes.size, BODY_SAMPLE_BYTES)
        val mime = it.contentType.orEmpty().substringBefore(';').lowercase()
        val binary = mime.startsWith("image/") || bytes.take(minOf(count, 256)).any { b ->
            val n = b.toInt() and 255
            n == 0 || (n < 32 && n != 9 && n != 10 && n != 13)
        }
        val candidate = if (binary) null else String(bytes, 0, count, charset)
        val text = candidate?.takeIf { s ->
            mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
                s.none { c -> c == '\uFFFD' || (c.isISOControl() && c !in "\n\r\t") }
        }
        val preview = text?.let { s -> MangaDebugRedactor.text(s, TEXT_PREVIEW_CHARS) }
        it.copy(actualBytes = bytes.size, sampledBytes = count,
            body = preview ?: "(图片或无法识别的二进制内容，不展示正文)",
            bodyTruncated = bytes.size > count || (text?.length ?: 0) > TEXT_PREVIEW_CHARS)
    }

    fun dataUri(size: Int) = record {
        it.copy(nonHttp = true, actualBytes = size, body = "(data URI；没有 HTTP Response，不展示编码数据)")
    }

    fun decrypted(configured: Boolean, size: Int?) = record {
        val result = when {
            !configured -> "未配置图片解密"
            size == null -> "解密失败或返回异常结果（decode 返回 null，具体异常不可得）"
            size == 0 -> "解密返回空数据"
            else -> "解密返回 $size 字节（尚不代表有效图片）"
        }
        it.copy(decrypt = result, failureStage = if (configured && (size == null || size == 0)) {
            "图片解密/空数据"
        } else it.failureStage)
    }

    fun validated(ok: Boolean) = record {
        it.copy(validation = if (ok) "图片数据校验通过" else "图片解码或校验失败（无具体解码异常）",
            failureStage = if (ok) it.failureStage else it.failureStage ?: "图片解码/校验")
    }

    fun cached(result: ImageCacheResult) = record {
        it.copy(cache = result,
            validation = if (result == ImageCacheResult.HIT) "已有持久缓存校验通过" else it.validation,
            decrypt = if (result == ImageCacheResult.HIT) "本次未解密（缓存命中）" else it.decrypt,
            failureStage = if (result == ImageCacheResult.FAILED) {
                it.failureStage ?: if (it.validation == "图片数据校验通过") {
                    "缓存处理（写入或回读校验未成功）"
                } else "图片缓存处理未成功（无更细结果）"
            } else it.failureStage)
    }

    fun failure(stage: String, error: Throwable) = record {
        if (error is CancellationException) it.copy(finished = true, failureStage = "任务取消")
        else it.copy(failureStage = it.failureStage ?: stage,
            error = MangaDebugRedactor.text("${error.javaClass.simpleName}: ${error.message.orEmpty()}", 2_000))
    }

    fun fetchFailed(error: Throwable) = record {
        if (it.failureStage != null) it else it.copy(failureStage = "URL 处理/获取图片数据",
            error = MangaDebugRedactor.text("${error.javaClass.simpleName}: ${error.message.orEmpty()}", 2_000))
    }

    fun displayFailed(fallback: Boolean = false, error: Throwable? = null) = record {
        val message = when {
            fallback -> "主路径显示失败；后续 Glide 兜底响应不采集"
            it.fallback -> "Glide 兜底最终显示失败（其响应未采集）"
            else -> "图片最终显示失败"
        }
        it.copy(display = message, fallback = it.fallback || fallback, finished = !fallback,
            failureStage = it.failureStage ?: "图片显示",
            error = error?.let { e -> MangaDebugRedactor.text("${e.javaClass.simpleName}: ${e.message.orEmpty()}", 2_000) } ?: it.error)
    }

    fun displayed() = record {
        it.copy(display = if (it.fallback) "Glide 兜底最终显示成功（其响应未采集）" else "图片最终显示成功",
            finished = true)
    }

    fun cancelled() = record {
        if (it.finished) it else it.copy(finished = true, failureStage = "任务取消", display = "未交付当前页面")
    }

    companion object {
        const val BODY_SAMPLE_BYTES = 64 * 1024
        const val TEXT_PREVIEW_CHARS = 32_000
    }
}

/** 与原 bytes() 共用唯一一次正文读取；headers 先提交，读失败时字节数保持未知。 */
internal suspend fun readObservedImageBytes(
    observer: ImageRequestObserver?,
    response: suspend () -> Response,
): ByteArray {
    if (observer == null) return response().body.bytes()
    var received = false
    try {
        val result = response()
        received = true
        observer.response(result)
        val bytes = result.body.bytes()
        val charset = runCatching { result.body.contentType()?.charset(Charsets.UTF_8) }.getOrNull() ?: Charsets.UTF_8
        observer.body(bytes, charset)
        return bytes
    } catch (e: Exception) {
        val stage = when {
            received -> "读取正文"
            e is UnknownHostException -> "DNS"
            e is SSLException -> "TLS"
            e is ConnectException -> "连接"
            else -> "获取 HTTP Response"
        }
        observer.failure(stage, e)
        throw e
    }
}
