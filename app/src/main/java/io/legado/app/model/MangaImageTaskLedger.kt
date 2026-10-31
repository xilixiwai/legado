package io.legado.app.model

enum class ImageDiscoveryState { OPEN, COMPLETE, INCOMPLETE }
enum class ImageTaskStatus { RUNNING, SUCCESS, PARTIAL_FAILED, ALL_FAILED, CANCELED, NO_RESOURCES }

data class ImageTaskSnapshot(
    val pageCount: Int,
    val uniqueResourceCount: Int,
    val processedCount: Int,
    val cacheHitCount: Int,
    /** 已处理可显示的新图，包含缓存写入失败；不是持久化成功数。 */
    val downloadedCount: Int,
    val failureCount: Int,
    val canceledCount: Int,
    val activeCount: Int,
    val pendingCount: Int,
    val cacheWriteFailureCount: Int,
    val parseFailureCount: Int,
    val emptyChapterCount: Int,
    val skippedVolumeCount: Int,
    val discovery: ImageDiscoveryState,
    val status: ImageTaskStatus,
) {
    val availableCount: Int get() = cacheHitCount + downloadedCount
    val storedCount: Int get() = downloadedCount - cacheWriteFailureCount
    val exactTotalResources: Int? get() = uniqueResourceCount.takeIf { discovery == ImageDiscoveryState.COMPLETE }
    /** 只有清单已完整才提供全书处理率；失败项也属于已处理。 */
    val processingFraction: Double?
        get() = exactTotalResources?.takeIf { it > 0 }?.let { processedCount.toDouble() / it }
    /** 可显示资源比例，不能与处理率混为一谈。 */
    val availabilityFraction: Double?
        get() = exactTotalResources?.takeIf { it > 0 }?.let { availableCount.toDouble() / it }
    /** 已处理资源中的可显示成功率；取消不当作失败，缓存写入失败仍可显示。 */
    val successFraction: Double?
        get() = processedCount.takeIf { it > 0 }?.let { availableCount.toDouble() / it }
}

/**
 * 不可变任务账本，无线程、网络、数据库或文件依赖。调用层负责串行使用返回的新实例。
 * pageId/章节 ID 是调用方稳定的 occurrence 标识；同 ID 重放幂等，不用页数替代资源数。
 */
class ImageTaskLedger private constructor(
    private val pages: Map<String, ResourceIdentity>,
    private val entries: Map<ResourceIdentity, Entry>,
    private val chapters: Map<String, ChapterState>,
    val discovery: ImageDiscoveryState,
    private val canceled: Boolean,
) {
    constructor() : this(emptyMap(), emptyMap(), emptyMap(), ImageDiscoveryState.OPEN, false)

    private data class Entry(
        val attempt: AttemptIdentity? = null,
        val seen: Set<AttemptIdentity> = emptySet(),
        val outcome: ImageOutcome? = null,
    )

    enum class ChapterState { PARSED, PARSE_FAILED, EMPTY, VOLUME }

    fun discover(pageId: String, resource: ResourceIdentity): ImageTaskLedger {
        require(pageId.isNotBlank())
        require(discovery == ImageDiscoveryState.OPEN) { "Reopen discovery before adding pages" }
        val previous = pages[pageId]
        require(previous == null || previous == resource) { "A page identity cannot change silently" }
        return next(pages = pages + (pageId to resource), entries = if (resource in entries) entries else entries + (resource to Entry()))
    }

    fun chapter(id: String, state: ChapterState): ImageTaskLedger {
        require(id.isNotBlank() && discovery == ImageDiscoveryState.OPEN)
        return next(chapters = chapters + (id to state))
    }

    fun finishDiscovery(): ImageTaskLedger = next(
        discovery = if (chapters.values.any { it == ChapterState.PARSE_FAILED }) {
            ImageDiscoveryState.INCOMPLETE
        } else ImageDiscoveryState.COMPLETE,
    )

    fun reopenDiscovery(): ImageTaskLedger = next(discovery = ImageDiscoveryState.OPEN)

    fun begin(attempt: AttemptIdentity): ImageTaskLedger {
        check(!canceled) { "Resume the task before starting an attempt" }
        val entry = entries[attempt.resource] ?: error("Discover the resource first")
        if (attempt in entry.seen) return this
        if (entry.outcome is ImageOutcome.CacheHit || entry.outcome is ImageOutcome.Stored) return this
        return next(entries = entries + (attempt.resource to Entry(attempt, entry.seen + attempt)))
    }

    /** attempt=null 只允许尚未开始请求的缓存命中；网络结果必须对应当前 attempt。 */
    fun record(outcome: ImageOutcome, attempt: AttemptIdentity? = null): ImageTaskLedger {
        val entry = entries[outcome.resource] ?: error("Discover the resource first")
        if (canceled) return this
        if (attempt == null) {
            require(outcome is ImageOutcome.CacheHit)
            if (entry.attempt != null || entry.outcome != null) return this
        } else {
            require(attempt.resource == outcome.resource)
            if (entry.attempt != attempt || entry.outcome?.terminal == true) return this
        }
        if (!outcome.terminal) return this
        return next(entries = entries + (outcome.resource to entry.copy(outcome = outcome)))
    }

    fun cancel(): ImageTaskLedger = next(
        canceled = true,
        entries = entries.mapValues { (resource, entry) ->
            if (entry.outcome?.terminal == true) entry else entry.copy(outcome = ImageOutcome.Canceled(resource))
        },
    )

    fun resume(): ImageTaskLedger = next(
        canceled = false,
        entries = entries.mapValues { (_, entry) ->
            if (entry.outcome is ImageOutcome.Canceled) entry.copy(attempt = null, outcome = null) else entry
        },
    )

    fun snapshot(): ImageTaskSnapshot {
        val outcomes = entries.values.mapNotNull { it.outcome }
        val hits = outcomes.count { it is ImageOutcome.CacheHit }
        val stored = outcomes.count { it is ImageOutcome.Stored }
        val writes = outcomes.count { it is ImageOutcome.CacheWriteFailed }
        val failed = outcomes.count { it is ImageOutcome.DownloadFailed || it is ImageOutcome.ProcessingFailed }
        val canceledItems = outcomes.count { it is ImageOutcome.Canceled }
        val active = entries.values.count { it.attempt != null && it.outcome == null }
        val processed = hits + stored + writes + failed
        val pending = entries.size - processed - canceledItems - active
        val parseErrors = chapters.values.count { it == ChapterState.PARSE_FAILED }
        val empty = chapters.values.count { it == ChapterState.EMPTY }
        val available = hits + stored + writes
        val errors = failed + writes + parseErrors + empty
        val status = when {
            canceled -> ImageTaskStatus.CANCELED
            discovery == ImageDiscoveryState.OPEN || pending > 0 || active > 0 -> ImageTaskStatus.RUNNING
            canceledItems > 0 -> ImageTaskStatus.CANCELED
            errors > 0 || discovery == ImageDiscoveryState.INCOMPLETE ->
                if (available > 0) ImageTaskStatus.PARTIAL_FAILED else ImageTaskStatus.ALL_FAILED
            entries.isEmpty() -> ImageTaskStatus.NO_RESOURCES
            else -> ImageTaskStatus.SUCCESS
        }
        return ImageTaskSnapshot(
            pages.size, entries.size, processed, hits, stored + writes, failed, canceledItems, active, pending,
            writes, parseErrors, empty, chapters.values.count { it == ChapterState.VOLUME }, discovery, status,
        )
    }

    private fun next(
        pages: Map<String, ResourceIdentity> = this.pages,
        entries: Map<ResourceIdentity, Entry> = this.entries,
        chapters: Map<String, ChapterState> = this.chapters,
        discovery: ImageDiscoveryState = this.discovery,
        canceled: Boolean = this.canceled,
    ): ImageTaskLedger = ImageTaskLedger(pages, entries, chapters, discovery, canceled)
}
