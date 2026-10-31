package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

/** 所有输入均为假数据；仅验证纯数据契约，不验证真实请求、解密或文件提交。 */
class MangaImageContractsTest {
    @Test
    fun `同书同资源身份稳定且attempt不影响缓存键`() {
        val first = imageResource()
        assertEquals(first, imageResource())
        val a = AttemptIdentity.fresh(first)
        val b = AttemptIdentity.fresh(first)
        assertNotEquals(a.attemptId, b.attemptId)
        assertEquals(a.resource.cacheKey, b.resource.cacheKey)
    }

    @Test
    fun `跨书跨源和不同密钥不会合并`() {
        val a = imageResource()
        assertNotEquals(a, imageResource(book = "book-two"))
        assertNotEquals(a, imageResource(source = "source-two"))
        assertNotEquals(a, imageResource(key = ByteArray(32) { 2 }))
    }

    @Test
    fun `原始src查询参数顺序token及请求选项完整参与摘要`() {
        val inputs = listOf(
            "https://cdn.test/a?a=1&token=one",
            "https://cdn.test/a?token=one&a=1",
            "https://cdn.test/a?a=1&token=two",
            "https://cdn.test/a?a=1&token=one,{\"headers\":{\"Referer\":\"one\"}}",
            "https://cdn.test/a?a=1&token=one,{\"headers\":{\"Referer\":\"two\"}}",
        )
        // 固定请求变体，单独证明原始 src 本身未丢失信息。
        val variant = imageVariant()
        assertEquals(inputs.size, inputs.map { imageResource(src = it, variant = variant).cacheKey }.toSet().size)
    }

    @Test
    fun `有效请求URL不改用最终重定向地址`() {
        assertNotEquals(imageVariant(url = "https://cdn.test/original"), imageVariant(url = "https://cdn.test/final"))
    }

    @Test
    fun `请求摘要不持有外部可变正文和头集合`() {
        val body = byteArrayOf(1)
        val values = mutableListOf("one")
        val variant = imageVariant(body = body, headers = mapOf("Cookie" to values))
        body[0] = 2
        values[0] = "two"
        assertEquals(imageVariant(body = byteArrayOf(1), headers = mapOf("Cookie" to listOf("one"))), variant)
        assertNotEquals(imageVariant(body = body, headers = mapOf("Cookie" to values)), variant)
    }

    @Test
    fun `Cookie Header POST正文及代理分区隔离内容变体`() {
        val base = imageVariant()
        val variants = listOf(
            base,
            imageVariant(headers = mapOf("Cookie" to listOf("session=one"))),
            imageVariant(headers = mapOf("Cookie" to listOf("session=two"))),
            imageVariant(headers = mapOf("Referer" to listOf("https://reader.test"))),
            imageVariant(method = "POST", body = byteArrayOf(1)),
            imageVariant(method = "POST", body = byteArrayOf(2)),
            imageVariant(proxy = "egress-two"),
            imageVariant(body = byteArrayOf()),
        )
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun `只规范化头名称顺序而不规范化头值或URL`() {
        assertEquals(
            imageVariant(headers = linkedMapOf("Cookie" to listOf("x"), "Accept" to listOf("a"))),
            imageVariant(headers = linkedMapOf("accept" to listOf("a"), "cookie" to listOf("x"))),
        )
        assertNotEquals(imageVariant(headers = mapOf("X-Test" to listOf("a", "b"))),
            imageVariant(headers = mapOf("X-Test" to listOf("b", "a"))))
        assertEquals(imageVariant(), imageVariant(headers = mapOf("Connection" to listOf("close"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `重复大小写头必须先明确合并不能静默覆盖`() {
        imageVariant(headers = mapOf("Cookie" to listOf("one"), "cookie" to listOf("two")))
    }

    @Test
    fun `解密规则库上下文及版本都影响身份`() {
        val identities = listOf(
            ImageDecodeIdentity.create(imageTestKey),
            ImageDecodeIdentity.create(imageTestKey, "decode-one"),
            ImageDecodeIdentity.create(imageTestKey, "decode-two"),
            ImageDecodeIdentity.create(imageTestKey, "decode-one", jsLibrary = "lib-two"),
            ImageDecodeIdentity.create(imageTestKey, "decode-one", contextPartition = "chapter-two"),
            ImageDecodeIdentity.create(imageTestKey, "decode-one", decoderVersion = 2),
        )
        assertEquals(identities.size, identities.map { imageResource(decode = it) }.toSet().size)
        assertFalse(identities.first().requiresDecoding)
        assertTrue(identities[1].requiresDecoding)
    }

    @Test
    fun `敏感输入不出现在身份字符串和文件键`() {
        val secret = "secret-cookie-token"
        val resource = imageResource(src = "https://cdn.test/a?token=$secret",
            variant = imageVariant(headers = mapOf("Cookie" to listOf(secret))),
            decode = ImageDecodeIdentity.create(imageTestKey, secret))
        val record = ImageProcessingRecord(resource, imageDigest, 10, ImageDecodeState.APPLIED)
        assertFalse(resource.toString().contains(secret))
        assertFalse(record.toString().contains(secret))
        assertTrue(resource.cacheKey.matches(Regex("[0-9a-f]{64}")))
        assertFalse(LegacyImageLocator("book", secret).toString().contains(secret))
    }

    @Test
    fun `网络完成处理完成与最终写入结果分离`() {
        val resource = imageResource()
        assertFalse(ImageOutcome.NetworkReceived(resource).terminal)
        assertFalse(ImageOutcome.NetworkReceived(resource).displayable)
        assertFalse(ImageOutcome.Processed(resource).terminal)
        assertTrue(ImageOutcome.Processed(resource).displayable)
        assertTrue(ImageOutcome.Stored(resource).terminal)
        assertTrue(ImageOutcome.CacheWriteFailed(resource).displayable)
        assertNotEquals(ImageOutcome.DownloadFailed(resource), ImageOutcome.CacheWriteFailed(resource))
        assertNotEquals(ImageOutcome.DownloadFailed(resource), ImageOutcome.Canceled(resource))
    }

    @Test
    fun `已知长度才提供百分比空响应和未知长度不伪造`() {
        val attempt = AttemptIdentity.fresh(imageResource())
        fun event(bytes: Long, total: Long?) = ImageProgressEvent(attempt, 1, ImageProgressPhase.RECEIVING, bytes, total)
        assertEquals(25, event(25, 100).networkPercent)
        assertNull(event(25, null).networkPercent)
        assertNull(event(0, 0).networkPercent)
        assertEquals(100, event(Long.MAX_VALUE, Long.MAX_VALUE).networkPercent)
        assertNull(ImageProgressEvent(attempt, 2, ImageProgressPhase.NETWORK_COMPLETE, 100, 100).networkPercent)
    }

    @Test
    fun `旧attempt进度和迟到的开始事件不能覆盖新attempt`() {
        val resource = imageResource()
        val first = AttemptIdentity(resource, "first")
        val second = AttemptIdentity(resource, "second")
        val current = ImageProgressState.start(first).begin(second)
            .accept(ImageProgressEvent(second, 1, ImageProgressPhase.RECEIVING, 10, 100))
        assertSame(current, current.accept(ImageProgressEvent(first, 100, ImageProgressPhase.REQUEST_STARTED, 0)))
        assertSame(current, current.accept(ImageProgressEvent(first, 101, ImageProgressPhase.FINAL, 100,
            outcome = ImageOutcome.Stored(resource))))
        assertSame(current, current.begin(first))
        assertEquals(second, current.attempt)
    }

    @Test
    fun `同attempt拒绝乱序字节回退阶段回退及终态后事件`() {
        val resource = imageResource()
        val attempt = AttemptIdentity.fresh(resource)
        var state = ImageProgressState.start(attempt)
            .accept(ImageProgressEvent(attempt, 2, ImageProgressPhase.RECEIVING, 10, 100))
        assertSame(state, state.accept(ImageProgressEvent(attempt, 1, ImageProgressPhase.RECEIVING, 20, 100)))
        assertSame(state, state.accept(ImageProgressEvent(attempt, 3, ImageProgressPhase.RECEIVING, 5, 100)))
        state = state.accept(ImageProgressEvent(attempt, 3, ImageProgressPhase.VALIDATING, 100, 100))
        assertSame(state, state.accept(ImageProgressEvent(attempt, 4, ImageProgressPhase.DECRYPTING, 100, 100)))
        state = state.accept(ImageProgressEvent(attempt, 4, ImageProgressPhase.FINAL, 100, 100,
            ImageOutcome.Canceled(resource)))
        assertTrue(state.latest?.outcome is ImageOutcome.Canceled)
        assertSame(state, state.accept(ImageProgressEvent(attempt, 5, ImageProgressPhase.FINAL, 100, 100,
            ImageOutcome.Stored(resource))))
    }

    @Test
    fun `重试快照从零开始且旧快照保持不变`() {
        val a = AttemptIdentity.fresh(imageResource())
        val old = ImageProgressState.start(a).accept(ImageProgressEvent(a, 1, ImageProgressPhase.RECEIVING, 100, 200))
        val fresh = old.begin(AttemptIdentity.fresh(a.resource))
        assertNull(fresh.latest)
        assertEquals(100L, old.latest?.bytesRead)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非终态结果不能被包装为最终成功`() {
        val a = AttemptIdentity.fresh(imageResource())
        ImageProgressEvent(a, 1, ImageProgressPhase.FINAL, 10, outcome = ImageOutcome.NetworkReceived(a.resource))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `结果资源必须与事件资源一致`() {
        val a = AttemptIdentity.fresh(imageResource())
        ImageProgressEvent(a, 1, ImageProgressPhase.FINAL, 10, outcome = ImageOutcome.Stored(imageResource(book = "other")))
    }

    @Test
    fun `记录匹配必须核对身份摘要长度与版本`() {
        val resource = imageResource()
        val record = ImageProcessingRecord(resource, imageDigest, 100, ImageDecodeState.NONE)
        assertTrue(record.matches(resource, imageDigest, 100))
        assertFalse(record.matches(imageResource(book = "other"), imageDigest, 100))
        assertFalse(record.matches(resource, ImageFingerprint("b".repeat(64)), 100))
        assertFalse(record.matches(resource, imageDigest, 101))
        assertFalse(record.copy(schemaVersion = 2).matches(resource, imageDigest, 100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知解密状态不能伪装为新处理记录`() {
        ImageProcessingRecord(imageResource(), imageDigest, 100, ImageDecodeState.UNKNOWN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `有规则的记录不能声明未执行解密`() {
        ImageProcessingRecord(imageResource(decode = ImageDecodeIdentity.create(imageTestKey, "rule")),
            imageDigest, 100, ImageDecodeState.NONE)
    }

    @Test
    fun `512MB软限制保护未知历史和活动缓存允许写入失败`() {
        assertFalse(mayReclaimImageCache(ImageDecodeState.UNKNOWN, false))
        assertFalse(mayReclaimImageCache(ImageDecodeState.APPLIED, true))
        assertTrue(mayReclaimImageCache(ImageDecodeState.NONE, false))
        val limit = 512L * 1024 * 1024
        assertTrue(assessImageCacheBudget(limit - 1, 1, 0).mayWrite)
        val blocked = assessImageCacheBudget(limit, 1, 0)
        assertFalse(blocked.mayWrite)
        assertEquals(1L, blocked.requiredReclaimBytes)
        assertTrue(assessImageCacheBudget(limit, 1, 1).mayWrite)
        assertFalse(assessImageCacheBudget(limit + 10, 1, 10).mayWrite)
        assertEquals(Long.MAX_VALUE, assessImageCacheBudget(Long.MAX_VALUE, Long.MAX_VALUE, 0).requiredReclaimBytes)
    }
}

internal val imageTestKey = ByteArray(32) { 1 }
internal val imageDigest = ImageFingerprint("a".repeat(64))

internal fun imageVariant(
    key: ByteArray = imageTestKey,
    url: String = "https://cdn.test/a",
    method: String = "GET",
    body: ByteArray? = null,
    headers: Map<String, List<String>> = emptyMap(),
    proxy: String = "",
): ImageRequestVariant = ImageRequestVariant.create(key, method, url, body, headers, proxy)

internal fun imageResource(
    book: String = "book-one",
    source: String = "source-one",
    src: String = "https://cdn.test/a",
    key: ByteArray = imageTestKey,
    variant: ImageRequestVariant = imageVariant(key = key, url = src),
    decode: ImageDecodeIdentity = ImageDecodeIdentity.create(key),
): ResourceIdentity = ResourceIdentity.create(key, book, source, src, variant, decode)
