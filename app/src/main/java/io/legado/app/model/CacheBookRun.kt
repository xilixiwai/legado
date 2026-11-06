package io.legado.app.model

import io.legado.app.exception.ConcurrentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

enum class CacheBookImageOutcome {
    QUEUED, RUNNING, RETRYING, SUCCESS, FAILED, NO_IMAGES, TEXT_UNAVAILABLE, TEXT_FAILED, SKIPPED, CANCELLED
}

/** Only complete returned batches are recorded; cancellation can retain a previous batch. */
data class CacheBookImageResult(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String?,
    val runId: Long,
    val attempts: Int,
    val outcome: CacheBookImageOutcome,
    val updatedAt: Long,
    val batch: ImageBatchStats?,
    val batchAt: Long?,
)

internal class CacheBookImageResults(private val limit: Int = 256) {
    private val entries = LinkedHashMap<Pair<String, Int>, CacheBookImageResult>()

    @Synchronized
    fun put(result: CacheBookImageResult) {
        val key = result.bookUrl to result.chapterIndex
        if ((entries[key]?.runId ?: 0) > result.runId) return
        entries.remove(key)
        entries[key] = result
        while (entries.size > limit) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun get(bookUrl: String, index: Int): CacheBookImageResult? = entries[bookUrl to index]

    @Synchronized
    fun forBook(bookUrl: String): List<CacheBookImageResult> =
        entries.values.filter { it.bookUrl == bookUrl }
}

/** CacheBook's chapter operations. Production adapters retain the existing text/cache APIs. */
internal interface CacheBookChapterWork {
    val chapterUrl: String
    val isManga: Boolean
    val isVolume: Boolean
    fun hasContent(): Boolean
    fun hasImages(): Boolean
    suspend fun readContent(): String?
    suspend fun fetchContent(): String
    suspend fun ensureImages(content: String): ImageBatchStats
    suspend fun saveTextImages(content: String)
    fun started() = Unit
    fun deliver(content: String)
    fun success()
    fun failure(error: Throwable, count: Int, terminal: Boolean)
    fun skipVolume()
}

/**
 * One book's CacheBook queue and preparation lease. All callback effects are committed under
 * this monitor, together with identity validation. Stop invalidates identities before cancel.
 * Jobs are lazy, registered before start, and cleaned up by completion even if never entered.
 */
internal class CacheBookRun(
    private val bookUrl: String,
    private val isManga: () -> Boolean,
    private val results: CacheBookImageResults,
    private val onPresent: () -> Unit = {},
    private val onIdle: () -> Unit = {},
    private val onChanged: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelay: suspend () -> Unit = { delay(1000) },
) {
    companion object {
        private val identities = AtomicLong()
    }

    internal class Attempt(val runId: Long, val id: Long, val index: Int, val number: Int) {
        var job: Job? = null
        var work: CacheBookChapterWork? = null
        var retry = false
        var terminal = false
    }

    internal class Preparation(val runId: Long, val id: Long) {
        var job: Job? = null
        val ranges = arrayListOf<IntRange>()
    }

    private var runId = 0L
    private var stopped = true
    private val queued = linkedSetOf<Int>()
    private val active = linkedMapOf<Int, Attempt>()
    private val attempts = hashMapOf<Int, Int>()
    private val errors = hashMapOf<Int, Int>()
    private var preparation: Preparation? = null

    @Synchronized fun isRunning() = queued.isNotEmpty() || active.isNotEmpty() || preparation != null
    @Synchronized fun isPreparing() = preparation != null
    @Synchronized fun waitCount() = queued.size
    @Synchronized fun activeCount() = active.size
    @Synchronized fun hasActive(index: Int) = index in active
    @Synchronized fun removeQueued(index: Int) { queued.remove(index) }

    private fun begin() {
        if (stopped || !isRunning()) {
            runId = identities.incrementAndGet()
            stopped = false
            attempts.clear()
            errors.clear()
        }
        onPresent()
    }

    @Synchronized
    fun add(start: Int, end: Int) {
        begin()
        enqueue(start..end)
        changed()
    }

    /** Keep the existing shared error budget when a foreground text request queues a retry. */
    @Synchronized
    fun retryFromReading(index: Int, failures: Int) {
        begin()
        errors[index] = failures
        attempts[index] = failures
        enqueue(index..index)
        changed()
    }

    private fun enqueue(range: IntRange) {
        range.forEach { index ->
            if (index !in active && queued.add(index)) {
                record(Attempt(runId, identities.incrementAndGet(), index, attempts[index] ?: 0),
                    CacheBookImageOutcome.QUEUED)
            }
        }
    }

    @Synchronized
    fun prepare(
        scope: CoroutineScope,
        context: CoroutineContext,
        start: Int,
        end: Int,
        load: suspend (Preparation) -> Int,
        failed: (Throwable) -> Unit,
    ): Job {
        begin()
        preparation?.let {
            it.ranges.add(start..end)
            return checkNotNull(it.job)
        }
        val token = Preparation(runId, identities.incrementAndGet())
        token.ranges.add(start..end)
        preparation = token
        val job = scope.launch(context, start = CoroutineStart.LAZY) {
            try {
                val lastIndex = load(token)
                currentCoroutineContext().ensureActive()
                withPreparation(token) {
                    token.ranges.forEach { range ->
                        val endIndex = if (range.last < 0) lastIndex else minOf(range.last, lastIndex)
                        enqueue(range.first..endIndex)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withPreparation(token) { failed(e) }
            }
        }
        token.job = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (preparation === token && runId == token.runId) {
                    preparation = null
                    changed()
                }
            }
        }
        onChanged()
        job.start()
        return job
    }

    /** Guard synchronous DB writes as well as queue commits against a stopped preparation. */
    @Synchronized
    fun <T> withPreparation(token: Preparation, action: () -> T): T {
        if (stopped || token.runId != runId || preparation !== token || token.job?.isCancelled == true) {
            throw CancellationException("CacheBook preparation superseded")
        }
        return action()
    }

    @Synchronized
    fun schedule(
        scope: CoroutineScope,
        context: CoroutineContext,
        chapter: (Int) -> CacheBookChapterWork?,
    ): Attempt? {
        // A cancelled old worker may still drain a buffered model emission after restart.
        if (!scope.isActive) return null
        val index = queued.firstOrNull() ?: run { changed(); return null }
        queued.remove(index)
        val token = Attempt(runId, identities.incrementAndGet(), index, (attempts[index] ?: 0) + 1)
        attempts[index] = token.number
        active[index] = token
        val job = scope.launch(context, start = CoroutineStart.LAZY) {
            try {
                val work = chapter(index) ?: run {
                    commit(token) { token.terminal = true; record(token, CacheBookImageOutcome.TEXT_UNAVAILABLE) }
                    return@launch
                }
                if (!commit(token) { token.work = work; work.started() }) return@launch
                execute(token, work)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Adapter/consumer errors are not mislabeled as failed text requests.
                commit(token) {
                    token.terminal = true
                    record(token, CacheBookImageOutcome.FAILED)
                    token.work?.failure(e, errors[index] ?: 0, true)
                }
            }
        }
        token.job = job
        job.invokeOnCompletion { finish(token, it) }
        job.start()
        return token
    }

    @Synchronized
    internal fun commit(token: Attempt, action: () -> Unit): Boolean {
        if (!current(token) || token.job?.isCancelled == true) return false
        action()
        return true
    }

    private fun current(token: Attempt) =
        !stopped && token.runId == runId && active[token.index] === token

    private suspend fun execute(token: Attempt, work: CacheBookChapterWork) {
        currentCoroutineContext().ensureActive()
        if (work.isVolume) {
            commit(token) {
                token.terminal = true
                record(token, CacheBookImageOutcome.SKIPPED)
                work.skipVolume()
            }
            return
        }
        if (!work.isManga && work.hasImages()) {
            commit(token) { token.terminal = true }
            return
        }
        if (!commit(token) { record(token, CacheBookImageOutcome.RUNNING) }) return
        val existing: Boolean
        val content: String?
        try {
            existing = work.hasContent()
            content = if (existing) work.readContent() else work.fetchContent()
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            retry(token, work, e, CacheBookImageOutcome.TEXT_FAILED, textFailure = true)
            return
        }
        if (content == null) {
            commit(token) {
                token.terminal = true
                record(token, CacheBookImageOutcome.TEXT_UNAVAILABLE)
            }
            return
        }
        // Deliver fetched text before image work, and never route image failures to text errors.
        if (!existing && !commit(token) { work.deliver(content) }) return
        currentCoroutineContext().ensureActive()
        if (work.isManga) {
            val batch = try {
                work.ensureImages(content).also { currentCoroutineContext().ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retry(token, work, e, CacheBookImageOutcome.FAILED)
                return
            }
            if (!commit(token) { record(token, CacheBookImageOutcome.RUNNING, batch) }) return
            if (batch.total == 0) {
                commit(token) { token.terminal = true; record(token, CacheBookImageOutcome.NO_IMAGES) }
                return
            }
            if (!(batch.total > 0 && batch.failed == 0 &&
                        batch.hit + batch.stored == batch.total && batch.allProcessed)) {
                retry(token, work, IllegalStateException("图片持久缓存未完成: $batch"), CacheBookImageOutcome.FAILED)
                return
            }
        } else if (existing) {
            // New text retains WebBook's original save-content/illustration behavior.
            try {
                work.saveTextImages(content)
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retry(token, work, e, CacheBookImageOutcome.FAILED)
                return
            }
        }
        commit(token) {
            token.terminal = true
            record(token, CacheBookImageOutcome.SUCCESS)
            work.success()
        }
    }

    private suspend fun retry(
        token: Attempt,
        work: CacheBookChapterWork,
        error: Throwable,
        outcome: CacheBookImageOutcome,
        textFailure: Boolean = false,
    ) {
        var again = false
        if (!commit(token) {
                val count = (errors[token.index] ?: 0) + if (error is ConcurrentException) 0 else 1
                errors[token.index] = count
                again = count < 3
                token.terminal = !again
                record(token, if (again) CacheBookImageOutcome.RETRYING else outcome)
                work.failure(error, count, !again)
                if (textFailure) work.deliver("获取正文失败\n${error.localizedMessage}")
                onChanged()
            }) return
        if (again) {
            retryDelay()
            currentCoroutineContext().ensureActive()
            commit(token) { token.retry = true }
        }
    }

    private fun record(token: Attempt, outcome: CacheBookImageOutcome, batch: ImageBatchStats? = null) {
        if (token.work?.isManga != true && !isManga()) return
        val previous = results.get(bookUrl, token.index)?.takeIf { it.runId == token.runId }
        val time = now()
        results.put(CacheBookImageResult(
            bookUrl, token.index, token.work?.chapterUrl ?: previous?.chapterUrl,
            token.runId, token.number, outcome, time,
            batch ?: previous?.batch, if (batch != null) time else previous?.batchAt,
        ))
    }

    @Synchronized
    internal fun finish(token: Attempt, cause: Throwable?) {
        if (!current(token)) return
        if (cause is CancellationException && !token.terminal) {
            record(token, CacheBookImageOutcome.CANCELLED)
        }
        active.remove(token.index)
        if (cause == null && token.retry) queued.add(token.index)
        changed()
    }

    private fun changed() {
        if (!isRunning()) onIdle()
        onChanged()
    }

    fun stop() {
        val jobs = synchronized(this) {
            if (stopped) return
            stopped = true
            queued.forEach { index ->
                record(Attempt(runId, identities.incrementAndGet(), index, attempts[index] ?: 0),
                    CacheBookImageOutcome.CANCELLED)
            }
            active.values.filterNot { it.terminal }.forEach { record(it, CacheBookImageOutcome.CANCELLED) }
            val jobs = active.values.mapNotNull { it.job } + listOfNotNull(preparation?.job)
            queued.clear()
            active.clear()
            preparation = null
            changed()
            jobs
        }
        jobs.forEach { it.cancel() }
    }
}
