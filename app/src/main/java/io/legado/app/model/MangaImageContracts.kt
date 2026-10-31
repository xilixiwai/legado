package io.legado.app.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 仅保存摘要；不持有请求头、Cookie、原始 URL 或规则文本。密钥由后续调用层提供。 */
data class ImageFingerprint(val hex: String) {
    init {
        require(hex.matches(Regex("[0-9a-f]{64}")))
    }
}

/**
 * 请求内容变体。保守包含所有端到端头；只排除已知逐跳传输头。
 * URL/query/body 不规范化。超时、重试预算、attemptId 不属于内容身份，故不接受这些参数。
 * 此工厂只做纯摘要计算，不准备请求、不访问 CookieStore、不保存密钥或请求快照。
 */
data class ImageRequestVariant(val fingerprint: ImageFingerprint) {
    companion object {
        private val transportHeaders = setOf(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding", "te", "trailer", "upgrade",
        )

        fun create(
            key: ByteArray,
            method: String,
            requestUrl: String,
            body: ByteArray? = null,
            effectiveHeaders: Map<String, List<String>> = emptyMap(),
            proxyPartition: String = "",
        ): ImageRequestVariant {
            require(method.isNotBlank() && requestUrl.isNotBlank())
            val headers = effectiveHeaders.entries.map { entry ->
                val name = entry.key.lowercase(Locale.ROOT)
                require(name.isNotBlank())
                name to entry.value
            }
            require(headers.map { it.first }.distinct().size == headers.size) {
                "Merge case-insensitive header names before computing identity"
            }
            val parts = mutableListOf(
                method.toByteArray(), requestUrl.toByteArray(),
                byteArrayOf(if (body == null) 0 else 1), body ?: byteArrayOf(), proxyPartition.toByteArray(),
            )
            headers.filterNot { it.first in transportHeaders }.sortedBy { it.first }.forEach { (name, values) ->
                parts.add(name.toByteArray())
                parts.add(values.size.toString().toByteArray())
                values.forEach { parts.add(it.toByteArray()) }
            }
            return ImageRequestVariant(imageHmac(key, "request-v1", parts))
        }
    }
}

data class ImageDecodeIdentity(val fingerprint: ImageFingerprint, val requiresDecoding: Boolean) {
    companion object {
        fun create(
            key: ByteArray,
            rule: String? = null,
            jsLibrary: String = "",
            contextPartition: String = "",
            decoderVersion: Int = 1,
        ): ImageDecodeIdentity {
            require(decoderVersion > 0)
            val required = !rule.isNullOrBlank()
            val parts = listOf(
                decoderVersion.toString(), if (required) rule.orEmpty() else "", jsLibrary, contextPartition,
            ).map { it.toByteArray() }
            return ImageDecodeIdentity(imageHmac(key, "decode-v1", parts), required)
        }
    }
}

/** book/source/src 分别摘要；完整 src（含 query、请求选项）逐字参与身份，不使用最终重定向 URL。 */
data class ResourceIdentity(
    val book: ImageFingerprint,
    val source: ImageFingerprint,
    val originalSrc: ImageFingerprint,
    val requestVariant: ImageRequestVariant,
    val decode: ImageDecodeIdentity,
) {
    /** 可作为后续记录/文件定位的安全摘要；与 attemptId、时间和压缩策略无关。 */
    val cacheKey: String
        get() = imageSha256(
            imageFrame(listOf(book.hex, source.hex, originalSrc.hex, requestVariant.fingerprint.hex,
                decode.fingerprint.hex, decode.requiresDecoding.toString()).map { it.toByteArray() }),
        ).hex

    companion object {
        fun create(
            key: ByteArray,
            bookId: String,
            sourceId: String,
            originalSrc: String,
            requestVariant: ImageRequestVariant,
            decode: ImageDecodeIdentity = ImageDecodeIdentity.create(key),
        ): ResourceIdentity {
            require(bookId.isNotBlank() && sourceId.isNotBlank() && originalSrc.isNotBlank())
            return ResourceIdentity(
                imageHmac(key, "book-v1", listOf(bookId.toByteArray())),
                imageHmac(key, "source-v1", listOf(sourceId.toByteArray())),
                imageHmac(key, "src-v1", listOf(originalSrc.toByteArray())), requestVariant, decode,
            )
        }
    }
}

data class AttemptIdentity(val resource: ResourceIdentity, val attemptId: String) {
    init {
        require(attemptId.isNotBlank())
    }

    companion object {
        fun fresh(resource: ResourceIdentity): AttemptIdentity = AttemptIdentity(resource, UUID.randomUUID().toString())
    }
}

enum class ImageDecodeState { UNKNOWN, NONE, APPLIED }

/** 不执行解密或文件读取；描述一份已经提交的图片与处理记录如何匹配。 */
data class ImageProcessingRecord(
    val resource: ResourceIdentity,
    val payloadDigest: ImageFingerprint,
    val byteLength: Long,
    val decodeState: ImageDecodeState,
    val schemaVersion: Int = 1,
) {
    init {
        require(byteLength > 0 && schemaVersion > 0)
        require(decodeState != ImageDecodeState.UNKNOWN)
        require((decodeState == ImageDecodeState.APPLIED) == resource.decode.requiresDecoding)
    }

    fun matches(identity: ResourceIdentity, digest: ImageFingerprint, length: Long): Boolean =
        schemaVersion == 1 && resource == identity && payloadDigest == digest && byteLength == length
}

/** 原目录/完整 src 只在内存用于旧查找；toString 不暴露 src 中的敏感请求选项。 */
data class LegacyImageLocator(val bookDirectory: String, val originalSrc: String) {
    init {
        require(bookDirectory.isNotBlank() && originalSrc.isNotBlank())
    }

    override fun toString(): String = "LegacyImageLocator(redacted)"
}

sealed interface ImageCacheSelection {
    data class Exact(val record: ImageProcessingRecord) : ImageCacheSelection
    data class Legacy(val locator: LegacyImageLocator, val readable: Boolean) : ImageCacheSelection {
        val decodeState: ImageDecodeState get() = ImageDecodeState.UNKNOWN
        val mayAutomaticallyDecode: Boolean get() = false
        val mayAutomaticallyDelete: Boolean get() = false
        val mayAutomaticallyReload: Boolean get() = false
        val mayAutomaticallyConfirm: Boolean get() = false
    }
    data object Missing : ImageCacheSelection
}

/**
 * 调用层提供只读探测结果；先精确新记录，再旧定位。不读写实际图片文件。
 * legacy 只在已发现旧文件时提供，不存在传 null；不可读旧文件也受保护，不能降为 Missing。
 */
fun selectImageCache(
    requested: ResourceIdentity,
    exactRecord: ImageProcessingRecord?,
    observedDigest: ImageFingerprint?,
    observedLength: Long?,
    exactFileReadable: Boolean,
    legacy: LegacyImageLocator?,
    legacyFileReadable: Boolean,
): ImageCacheSelection {
    if (exactRecord != null && observedDigest != null && observedLength != null && exactFileReadable &&
        exactRecord.matches(requested, observedDigest, observedLength)
    ) return ImageCacheSelection.Exact(exactRecord)
    return if (legacy != null) ImageCacheSelection.Legacy(legacy, legacyFileReadable) else ImageCacheSelection.Missing
}

enum class ImageCacheHitOrigin { EXACT, LEGACY_UNKNOWN }

/** 网络/处理是里程碑，Stored 才是持久化成功；写盘失败仍允许展示已处理的图片。 */
sealed interface ImageOutcome {
    val resource: ResourceIdentity
    val terminal: Boolean get() = true
    val displayable: Boolean get() = false

    /** resource 是本次请求身份；默认不声称命中的旧文件已匹配当前变体或已解密。 */
    data class CacheHit(
        override val resource: ResourceIdentity,
        val origin: ImageCacheHitOrigin = ImageCacheHitOrigin.LEGACY_UNKNOWN,
    ) : ImageOutcome {
        override val displayable: Boolean get() = true
    }
    data class NetworkReceived(override val resource: ResourceIdentity) : ImageOutcome {
        override val terminal: Boolean get() = false
    }
    data class Processed(override val resource: ResourceIdentity) : ImageOutcome {
        override val terminal: Boolean get() = false
        override val displayable: Boolean get() = true
    }
    data class Stored(override val resource: ResourceIdentity) : ImageOutcome {
        override val displayable: Boolean get() = true
    }
    data class DownloadFailed(override val resource: ResourceIdentity) : ImageOutcome
    data class ProcessingFailed(override val resource: ResourceIdentity) : ImageOutcome
    data class CacheWriteFailed(override val resource: ResourceIdentity) : ImageOutcome {
        override val displayable: Boolean get() = true
    }
    data class Canceled(override val resource: ResourceIdentity) : ImageOutcome
}

enum class ImageProgressPhase {
    REQUEST_STARTED, RECEIVING, NETWORK_COMPLETE, DECRYPTING, VALIDATING, OPTIMIZING, SAVING, FINAL,
}

/**
 * 不含可变数组/集合、UI 或时间源；sequence 在每个 attempt 内严格递增。
 * bytesRead 是累计网络字节，进入处理/终态仍需携带；null 表示总长度未知，0 表示已知空响应。
 * 调用层仅在长度可信且与读取字节口径一致时提供 totalBytes（解压后的读数不可套用压缩长度）。
 */
data class ImageProgressEvent(
    val attempt: AttemptIdentity,
    val sequence: Long,
    val phase: ImageProgressPhase,
    val bytesRead: Long,
    val totalBytes: Long? = null,
    val outcome: ImageOutcome? = null,
) {
    val resource: ResourceIdentity get() = attempt.resource

    init {
        require(sequence >= 0 && bytesRead >= 0)
        require(totalBytes == null || totalBytes >= 0)
        require(totalBytes == null || bytesRead <= totalBytes)
        require((phase == ImageProgressPhase.FINAL) == (outcome != null))
        require(outcome == null || (outcome.terminal && outcome.resource == resource))
    }

    val networkPercent: Int?
        get() = if (phase == ImageProgressPhase.RECEIVING && totalBytes != null && totalBytes > 0) {
            (bytesRead.toDouble() / totalBytes * 100).toInt().coerceIn(0, 100)
        } else null
}

/** 纯 reducer。切换 attempt 必须显式 begin；迟到的 REQUEST_STARTED 也不能自动抢占当前请求。 */
class ImageProgressState private constructor(
    val attempt: AttemptIdentity,
    val latest: ImageProgressEvent?,
    private val seenAttempts: Set<AttemptIdentity>,
) {
    fun begin(next: AttemptIdentity): ImageProgressState {
        require(next.resource == attempt.resource)
        return if (next in seenAttempts) this else ImageProgressState(next, null, seenAttempts + next)
    }

    fun accept(event: ImageProgressEvent): ImageProgressState {
        if (event.attempt != attempt) return this
        val previous = latest
        if (previous != null && (previous.phase == ImageProgressPhase.FINAL || event.sequence <= previous.sequence ||
                event.phase.ordinal < previous.phase.ordinal || event.bytesRead < previous.bytesRead)
        ) return this
        return ImageProgressState(attempt, event, seenAttempts)
    }

    companion object {
        fun start(attempt: AttemptIdentity): ImageProgressState = ImageProgressState(attempt, null, setOf(attempt))
    }
}

data class ImageCacheBudgetDecision(val mayWrite: Boolean, val requiredReclaimBytes: Long)

/** 只允许确认过的新缓存进入后续裁剪候选；未知历史缓存和活动/保护资源一律不回收。 */
fun mayReclaimImageCache(decodeState: ImageDecodeState, protected: Boolean): Boolean =
    decodeState != ImageDecodeState.UNKNOWN && !protected

/** 只计算软上限可行性；未知旧缓存绝不列入可回收量，不实施裁剪。 */
fun assessImageCacheBudget(
    usedBytes: Long,
    incomingBytes: Long,
    reclaimableBytes: Long,
    softLimitBytes: Long = 512L * 1024 * 1024,
): ImageCacheBudgetDecision {
    require(usedBytes >= 0 && incomingBytes >= 0 && reclaimableBytes in 0..usedBytes && softLimitBytes > 0)
    val retained = usedBytes - reclaimableBytes
    val mayWrite = incomingBytes <= softLimitBytes && retained <= softLimitBytes - incomingBytes
    val free = (softLimitBytes - usedBytes).coerceAtLeast(0)
    val required = if (usedBytes > softLimitBytes) {
        val excess = usedBytes - softLimitBytes
        if (incomingBytes > Long.MAX_VALUE - excess) Long.MAX_VALUE else excess + incomingBytes
    } else (incomingBytes - free).coerceAtLeast(0)
    return ImageCacheBudgetDecision(mayWrite, required)
}

private fun imageFrame(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
    DataOutputStream(output).use { data ->
        parts.forEach { data.writeInt(it.size); data.write(it) }
    }
    output.toByteArray()
}

private fun imageHmac(key: ByteArray, domain: String, parts: List<ByteArray>): ImageFingerprint {
    require(key.size >= 16) { "Supply a stable application-private identity key" }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return ImageFingerprint(mac.doFinal(imageFrame(listOf(domain.toByteArray()) + parts)).imageHex())
}

private fun imageSha256(bytes: ByteArray): ImageFingerprint =
    ImageFingerprint(MessageDigest.getInstance("SHA-256").digest(bytes).imageHex())

private fun ByteArray.imageHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
