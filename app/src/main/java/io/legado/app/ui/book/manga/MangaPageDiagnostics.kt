package io.legado.app.ui.book.manga

import io.legado.app.help.http.ImageRequestObserver
import io.legado.app.help.http.ImageRequestSnapshot
import io.legado.app.model.ImageCacheResult
import io.legado.app.utils.MangaDebugRedactor
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 用完整输入作 SHA-256 身份，不保留原始 URL/凭据；绝不使用脱敏 URL 作键。 */
@ConsistentCopyVisibility
data class MangaPageIdentity private constructor(
    val context: String, val chapter: String, val page: Int, val image: String,
) {
    companion object {
        private fun digest(vararg values: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            values.forEach { hash.update(it.length.toString().toByteArray()); hash.update(0); hash.update(it.toByteArray()) }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun context(book: String, source: String) = digest(book, source)
        fun create(book: String, source: String, chapter: String, page: Int, url: String) =
            MangaPageIdentity(context(book, source), digest(chapter), page, digest(url))
    }
}

/** Activity 会话独占；同步身份检查+提交，迟到回调不能复活被替换/逐出/关闭的记录。 */
class MangaPageDiagnostics(
    private val maxRecords: Int = 8,
    private val maxChars: Int = 128_000,
) {
    private data class Entry(val requestId: Long, val snapshot: ImageRequestSnapshot)
    private val records = LinkedHashMap<MangaPageIdentity, Entry>()
    private var nextId = 0L
    private var generation = 0L
    private var closed = false
    private var context: String? = null

    @Synchronized
    fun begin(page: MangaPageIdentity, inputUrl: String): ImageRequestObserver {
        if (context != page.context) { clear(); context = page.context }
        val id = ++nextId
        val session = generation
        if (!closed) {
            records.remove(page)
            records[page] = Entry(id, ImageRequestSnapshot(MangaDebugRedactor.url(inputUrl), System.currentTimeMillis(),
                nonHttp = inputUrl.startsWith("data:", true)))
            trim()
        }
        return ImageRequestObserver { edit ->
            synchronized(this) {
                val old = records[page]
                if (!closed && session == generation && old?.requestId == id && !old.snapshot.finished) {
                    records[page] = old.copy(snapshot = edit(old.snapshot))
                    trim()
                }
            }
        }
    }

    @Synchronized fun snapshot(page: MangaPageIdentity?): ImageRequestSnapshot? = records[page]?.snapshot
    @Synchronized fun requestId(page: MangaPageIdentity): Long? = records[page]?.requestId
    @Synchronized fun size(): Int = records.size
    @Synchronized fun retainedChars(): Int = records.values.sumOf { it.snapshot.retainedChars }
    @Synchronized fun clear() { generation++; records.clear(); context = null }
    @Synchronized fun close() { clear(); closed = true }

    private fun trim() {
        while (records.isNotEmpty() && (records.size > maxRecords || retainedChars() > maxChars)) {
            records.remove(records.keys.first())
        }
    }
}

object MangaResponseFormatter {
    fun format(info: ImageRequestSnapshot?): String {
        if (info == null) return "当前页面暂无请求记录"
        return buildString {
            appendLine("【当前漫画页图片请求】")
            appendLine("已做基础脱敏；正文仍可能包含站点数据，请勿公开分享。")
            if (!info.finished) appendLine("请求仍在进行中")
            appendLine("【请求信息】")
            appendLine("输入 URL: ${info.inputUrl}")
            info.requestUrl?.let { appendLine("实际请求 URL: $it") }
            info.finalUrl?.let { appendLine("最终响应 URL: $it") }
            val time = info.requestAt ?: info.startedAt
            appendLine("${if (info.requestAt == null) "绑定" else "请求"}时间: " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date(time)))
            appendLine("【HTTP 响应】")
            when {
                info.cache == ImageCacheResult.HIT -> {
                    appendLine("本次使用已有缓存，没有发起新的 HTTP 请求（主路径）")
                    if (info.fallback) appendLine("随后进入独立 Glide 兜底；其网络响应未采集")
                }
                info.nonHttp -> appendLine("data URI：没有 HTTP Response")
                !info.hasResponse -> appendLine("没有取得 HTTP Response")
                else -> {
                    appendLine("HTTP 状态码: ${info.status}")
                    appendLine("Content-Type: ${info.contentType ?: "(未提供)"}")
                    appendLine("Content-Length（响应头）: ${info.declaredLength ?: "(未提供)"}")
                    appendLine("Response Headers:\n${info.headers.orEmpty()}")
                }
            }
            appendLine("实际读取正文大小: ${info.actualBytes?.let { "$it 字节" } ?: "未知/尚未读完"}")
            if (info.body != null) {
                appendLine("【正文预览：网络层交付的正文（data URI 除外）】")
                info.sampledBytes?.let { appendLine("采样 $it 字节；最多 65536 字节 / 32000 字符") }
                if (info.bodyTruncated) appendLine("正文预览已截断")
                appendLine(info.body)
            }
            appendLine("【图片处理结果】")
            appendLine("解密: ${info.decrypt ?: "未执行/尚未取得结果"}")
            appendLine("解码及校验: ${info.validation ?: "未执行/尚未取得结果"}")
            appendLine("缓存: ${info.cache?.name ?: "尚未取得结果"}")
            if (info.cache == ImageCacheResult.STORED) appendLine("有效图片已写入持久缓存并通过回读校验")
            appendLine("显示: ${info.display ?: "尚未取得结果"}")
            info.failureStage?.let { appendLine("【异常信息】\n失败阶段: $it") }
            info.error?.let { appendLine("异常摘要: $it") }
        }
    }
}
