package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class MangaImageTaskLedgerTest {
    private val a = imageResource()
    private val b = imageResource(src = "https://cdn.test/b")

    @Test
    fun `ABA为三页两资源重放页面幂等`() {
        val ledger = ImageTaskLedger().discover("1", a).discover("2", b).discover("3", a).discover("1", a)
        val snapshot = ledger.snapshot()
        assertEquals(3, snapshot.pageCount)
        assertEquals(2, snapshot.uniqueResourceCount)
        assertEquals(2, snapshot.pendingCount)
        assertNull(snapshot.exactTotalResources)
        assertNull(snapshot.processingFraction)
        assertNull(snapshot.availabilityFraction)
    }

    @Test
    fun `相同URL的不同请求变体分别统计`() {
        val variant = imageResource(variant = imageVariant(headers = mapOf("Cookie" to listOf("two"))))
        assertEquals(2, ImageTaskLedger().discover("1", a).discover("2", variant).snapshot().uniqueResourceCount)
    }

    @Test
    fun `命中下载失败取消及当前处理独立计数`() {
        val resources = (1..6).map { imageResource(src = "https://cdn.test/$it") }
        var ledger = ImageTaskLedger()
        resources.forEachIndexed { index, resource -> ledger = ledger.discover("$index", resource) }
        ledger = ledger.record(ImageOutcome.CacheHit(resources[0]))
        val outcomes = listOf(
            ImageOutcome.Stored(resources[1]), ImageOutcome.DownloadFailed(resources[2]),
            ImageOutcome.ProcessingFailed(resources[3]), ImageOutcome.Canceled(resources[4]),
        )
        outcomes.forEach { outcome ->
            val attempt = AttemptIdentity.fresh(outcome.resource)
            ledger = ledger.begin(attempt).record(outcome, attempt)
        }
        ledger = ledger.begin(AttemptIdentity.fresh(resources[5])).finishDiscovery()
        val snapshot = ledger.snapshot()
        assertEquals(1, snapshot.cacheHitCount)
        assertEquals(1, snapshot.downloadedCount)
        assertEquals(2, snapshot.failureCount)
        assertEquals(1, snapshot.canceledCount)
        assertEquals(1, snapshot.activeCount)
        assertEquals(4, snapshot.processedCount)
        assertEquals(0, snapshot.pendingCount)
        assertEquals(0.5, snapshot.successFraction!!, 0.0)
        assertEquals(ImageTaskStatus.RUNNING, snapshot.status)
    }

    @Test
    fun `网络和处理里程碑不能提前计最终完成`() {
        val attempt = AttemptIdentity.fresh(a)
        val ledger = ImageTaskLedger().discover("1", a).begin(attempt)
            .record(ImageOutcome.NetworkReceived(a), attempt).record(ImageOutcome.Processed(a), attempt)
        assertEquals(0, ledger.snapshot().processedCount)
        assertEquals(1, ledger.snapshot().activeCount)
        assertEquals(0, ledger.snapshot().downloadedCount)
    }

    @Test
    fun `写盘失败保留可显示成功并与网络失败分开`() {
        val attempt = AttemptIdentity.fresh(a)
        val snapshot = ImageTaskLedger().discover("1", a).begin(attempt)
            .record(ImageOutcome.CacheWriteFailed(a), attempt).finishDiscovery().snapshot()
        assertEquals(1, snapshot.downloadedCount)
        assertEquals(1, snapshot.availableCount)
        assertEquals(0, snapshot.storedCount)
        assertEquals(0, snapshot.failureCount)
        assertEquals(1, snapshot.cacheWriteFailureCount)
        assertEquals(ImageTaskStatus.PARTIAL_FAILED, snapshot.status)
        assertEquals(1.0, snapshot.processingFraction!!, 0.0)
        assertEquals(1.0, snapshot.successFraction!!, 0.0)
    }

    @Test
    fun `重试不增加分母旧attempt和重复回调不重复计数`() {
        val first = AttemptIdentity(a, "first")
        val second = AttemptIdentity(a, "second")
        val failed = ImageTaskLedger().discover("1", a).begin(first).record(ImageOutcome.DownloadFailed(a), first)
        val retry = failed.begin(second)
        assertEquals(1, failed.snapshot().failureCount)
        assertEquals(0, retry.snapshot().failureCount)
        assertEquals(1, retry.snapshot().activeCount)
        assertSame(retry, retry.begin(first))
        assertSame(retry, retry.record(ImageOutcome.Stored(a), first))
        val done = retry.record(ImageOutcome.Stored(a), second)
        assertSame(done, done.record(ImageOutcome.DownloadFailed(a), second))
        assertEquals(1, done.snapshot().uniqueResourceCount)
        assertEquals(1, done.snapshot().processedCount)
        assertEquals(1, done.snapshot().downloadedCount)
    }

    @Test
    fun `部分失败处理完成率与可用率分开`() {
        val attempt = AttemptIdentity.fresh(b)
        val snapshot = ImageTaskLedger().discover("1", a).discover("2", b)
            .record(ImageOutcome.CacheHit(a)).begin(attempt).record(ImageOutcome.DownloadFailed(b), attempt)
            .finishDiscovery().snapshot()
        assertEquals(ImageTaskStatus.PARTIAL_FAILED, snapshot.status)
        assertEquals(1.0, snapshot.processingFraction!!, 0.0)
        assertEquals(0.5, snapshot.availabilityFraction!!, 0.0)
        assertEquals(0.5, snapshot.successFraction!!, 0.0)
    }

    @Test
    fun `全部失败与全部成功区分`() {
        val attempt = AttemptIdentity.fresh(a)
        val base = ImageTaskLedger().discover("1", a).begin(attempt).finishDiscovery()
        assertEquals(ImageTaskStatus.ALL_FAILED, base.record(ImageOutcome.ProcessingFailed(a), attempt).snapshot().status)
        assertEquals(ImageTaskStatus.SUCCESS, base.record(ImageOutcome.Stored(a), attempt).snapshot().status)
    }

    @Test
    fun `取消保留已完成项继续时只重置取消项`() {
        val old = AttemptIdentity(b, "old")
        val ledger = ImageTaskLedger().discover("1", a).discover("2", b)
            .record(ImageOutcome.CacheHit(a)).begin(old).finishDiscovery().cancel()
        assertEquals(ImageTaskStatus.CANCELED, ledger.snapshot().status)
        assertEquals(1, ledger.snapshot().cacheHitCount)
        assertEquals(1, ledger.snapshot().canceledCount)
        assertEquals(1, ledger.snapshot().processedCount)
        assertSame(ledger, ledger.record(ImageOutcome.Stored(b), old))
        val resumed = ledger.resume()
        assertSame(resumed, resumed.begin(old))
        assertSame(resumed, resumed.record(ImageOutcome.Stored(b), old))
        val fresh = AttemptIdentity(b, "new")
        val done = resumed.begin(fresh).record(ImageOutcome.Stored(b), fresh)
        assertEquals(ImageTaskStatus.SUCCESS, done.snapshot().status)
        assertEquals(0, done.snapshot().canceledCount)
        assertEquals(1, done.snapshot().cacheHitCount)
    }

    @Test
    fun `动态发现即使现有全部命中也不报告全书百分比`() {
        val ledger = ImageTaskLedger().discover("1", a).record(ImageOutcome.CacheHit(a))
        assertNull(ledger.snapshot().processingFraction)
        assertEquals(1.0, ledger.snapshot().successFraction!!, 0.0)
        assertEquals(ImageTaskStatus.RUNNING, ledger.snapshot().status)
        val complete = ledger.finishDiscovery()
        assertEquals(1.0, complete.snapshot().processingFraction!!, 0.0)
        val expanded = complete.reopenDiscovery().discover("2", b)
        assertNull(expanded.snapshot().exactTotalResources)
        assertNull(expanded.snapshot().processingFraction)
        assertEquals(1, complete.snapshot().uniqueResourceCount)
    }

    @Test
    fun `解析失败不能声称发现完整总数解析重试可以恢复`() {
        val ledger = ImageTaskLedger().chapter("chapter", ImageTaskLedger.ChapterState.PARSE_FAILED).finishDiscovery()
        assertEquals(ImageDiscoveryState.INCOMPLETE, ledger.snapshot().discovery)
        assertEquals(ImageTaskStatus.ALL_FAILED, ledger.snapshot().status)
        assertEquals(1, ledger.snapshot().parseFailureCount)
        assertNull(ledger.snapshot().exactTotalResources)
        val retry = ledger.reopenDiscovery().chapter("chapter", ImageTaskLedger.ChapterState.PARSED)
            .discover("chapter/1", a).record(ImageOutcome.CacheHit(a)).finishDiscovery()
        assertEquals(0, retry.snapshot().parseFailureCount)
        assertEquals(ImageTaskStatus.SUCCESS, retry.snapshot().status)
    }

    @Test
    fun `空章节为独立异常卷标题不当图片或失败`() {
        val volume = ImageTaskLedger().chapter("volume", ImageTaskLedger.ChapterState.VOLUME).finishDiscovery().snapshot()
        assertEquals(1, volume.skippedVolumeCount)
        assertEquals(0, volume.uniqueResourceCount)
        assertEquals(ImageTaskStatus.NO_RESOURCES, volume.status)
        val empty = ImageTaskLedger().chapter("empty", ImageTaskLedger.ChapterState.EMPTY).finishDiscovery().snapshot()
        assertEquals(1, empty.emptyChapterCount)
        assertEquals(0, empty.failureCount)
        assertEquals(ImageTaskStatus.ALL_FAILED, empty.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `同页面身份不能静默换资源`() {
        ImageTaskLedger().discover("1", a).discover("1", b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `清单关闭后必须显式重新开启发现`() {
        ImageTaskLedger().finishDiscovery().discover("1", a)
    }
}
