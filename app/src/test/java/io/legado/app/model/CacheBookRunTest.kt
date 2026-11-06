package io.legado.app.model

import io.legado.app.exception.ConcurrentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Exercises the production queue, attempt runner, preparation leases and real cache contract.
 * No appDb, Android service, personal source data or existing cache is initialized here. */
class CacheBookRunTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Work(
        override val isManga: Boolean = true,
        override val isVolume: Boolean = false,
        var existing: Boolean = true,
    ) : CacheBookChapterWork {
        override val chapterUrl = "https://example.test/chapter"
        var content: String? = "real chapter"
        var alreadyCached = false
        var fetch: suspend () -> String = { "real chapter" }
        var images: suspend () -> ImageBatchStats = { ImageBatchStats(2, stored = 2) }
        var read: suspend () -> String? = { content }
        var reads = 0
        var fetches = 0
        var batches = 0
        var illustrations = 0
        var successes = 0
        var volumes = 0
        val deliveries = arrayListOf<String>()
        val failures = arrayListOf<Pair<Int, Boolean>>()
        override fun hasContent() = existing
        override fun hasImages() = alreadyCached
        override suspend fun readContent(): String? { reads++; return read() }
        override suspend fun fetchContent(): String {
            fetches++
            return fetch().also { content = it; existing = true }
        }
        override suspend fun ensureImages(content: String): ImageBatchStats { batches++; return images() }
        override suspend fun saveTextImages(content: String) { illustrations++ }
        override fun deliver(content: String) { deliveries.add(content) }
        override fun success() { successes++ }
        override fun failure(error: Throwable, count: Int, terminal: Boolean) { failures.add(count to terminal) }
        override fun skipVolume() { volumes++ }
    }

    private class Fixture(
        val work: Work = Work(),
        val results: CacheBookImageResults = CacheBookImageResults(),
        val book: String = "book",
        retry: suspend () -> Unit = {},
    ) {
        var present = false
        var removals = 0
        var clock = 100L
        val run = CacheBookRun(book, { work.isManga }, results,
            onPresent = { present = true },
            onIdle = { present = false; removals++ },
            now = { clock++ }, retryDelay = retry)
        fun result(index: Int = 0) = results.get(book, index)!!
        suspend fun drain() {
            var limit = 0
            while (run.isRunning()) {
                check(++limit < 10) { "run failed to terminate" }
                coroutineScope { run.schedule(this, EmptyCoroutineContext) { work } }
            }
        }
    }

    private fun verifySuccess(stats: ImageBatchStats) = runBlocking {
        for (existing in listOf(true, false)) {
            val f = Fixture(Work(existing = existing).apply { images = { stats } })
            f.run.add(0, 0)
            f.drain()
            assertEquals(1, f.work.successes)
            assertEquals(1, f.work.batches)
            assertEquals(stats, f.result().batch)
            assertEquals(CacheBookImageOutcome.SUCCESS, f.result().outcome)
            assertEquals(if (existing) emptyList<String>() else listOf("real chapter"), f.work.deliveries)
            assertFalse(f.present)
        }
    }

    @Test fun `both text branches accept all persistent hits`() = verifySuccess(ImageBatchStats(2, hit = 2))
    @Test fun `both text branches accept all stored images`() = verifySuccess(ImageBatchStats(2, stored = 2))
    @Test fun `both text branches accept mixed hit and stored`() = verifySuccess(ImageBatchStats(2, hit = 1, stored = 1))

    private fun verifyFailure(stats: ImageBatchStats) = runBlocking {
        for (existing in listOf(true, false)) {
            val f = Fixture(Work(existing = existing).apply { images = { stats } })
            f.run.add(0, 0)
            f.drain()
            assertEquals(3, f.work.batches)
            assertEquals(0, f.work.successes)
            assertEquals(listOf(1 to false, 2 to false, 3 to true), f.work.failures)
            assertEquals(stats, f.result().batch)
            assertEquals(3, f.result().attempts)
            assertEquals(CacheBookImageOutcome.FAILED, f.result().outcome)
            assertFalse(f.work.deliveries.any { it.startsWith("获取正文失败") })
            if (!existing) assertEquals(listOf("real chapter"), f.work.deliveries)
        }
    }

    @Test fun `both text branches retry partial failures three total attempts`() = verifyFailure(ImageBatchStats(2, hit = 1, failed = 1))
    @Test fun `both text branches retry all failures three total attempts`() = verifyFailure(ImageBatchStats(2, failed = 2))
    @Test fun `one success among 200 images never completes chapter`() = verifyFailure(ImageBatchStats(200, stored = 1, failed = 199))
    @Test fun `incomplete batch is not a success despite allProcessed field`() = verifyFailure(ImageBatchStats(2, hit = 1))

    @Test fun `both branches stop after zero images without automatic retries`() = runBlocking {
        for (existing in listOf(true, false)) {
            val f = Fixture(Work(existing = existing).apply { images = { ImageBatchStats(0) } })
            f.run.add(0, 0)
            f.drain()
            assertEquals(1, f.work.batches)
            assertEquals(0, f.work.successes)
            assertEquals(CacheBookImageOutcome.NO_IMAGES, f.result().outcome)
            assertTrue(f.work.failures.isEmpty())
        }
    }

    @Test fun `missing cached text is unavailable and never image success`() = runBlocking {
        val f = Fixture(Work().apply { content = null })
        f.run.add(0, 0)
        f.drain()
        assertEquals(CacheBookImageOutcome.TEXT_UNAVAILABLE, f.result().outcome)
        assertEquals(0, f.work.batches)
        assertEquals(0, f.work.successes)
        assertEquals(0, f.work.fetches)
    }

    @Test fun `volume is skipped before text or images`() = runBlocking {
        val f = Fixture(Work(isVolume = true))
        f.run.add(0, 0)
        f.drain()
        assertEquals(1, f.work.volumes)
        assertEquals(0, f.work.reads)
        assertEquals(0, f.work.batches)
        assertEquals(CacheBookImageOutcome.SKIPPED, f.result().outcome)
    }

    @Test fun `ordinary text chapters keep both existing illustration and fetched text semantics`() = runBlocking {
        for (existing in listOf(true, false)) {
            val f = Fixture(Work(isManga = false, existing = existing))
            f.run.add(0, 0)
            f.drain()
            assertEquals(1, f.work.successes)
            assertEquals(0, f.work.batches)
            assertEquals(if (existing) 1 else 0, f.work.illustrations)
            assertNull(f.results.get(f.book, 0))
        }
    }

    @Test fun `text complete fast path remains but manga fast path records hits`() = runBlocking {
        val text = Fixture(Work(isManga = false).apply { alreadyCached = true })
        text.run.add(0, 0)
        text.drain()
        assertEquals(0, text.work.reads)
        val manga = Fixture(Work().apply { alreadyCached = true; images = { ImageBatchStats(2, hit = 2) } })
        manga.run.add(0, 0)
        manga.drain()
        assertEquals(1, manga.work.batches)
        assertEquals(2, manga.result().batch!!.hit)
    }

    @Test fun `retry uses persisted images and replaces rather than sums batch stats`() = runBlocking {
        val root = temporary.newFolder()
        val store = object : ImageStore {
            override fun exists(key: String) = File(root, key).exists()
            override fun isUsable(key: String) = File(root, key).takeIf { it.isFile }?.readBytes()?.contentEquals(byteArrayOf(1)) == true
            override fun delete(key: String) { File(root, key).delete() }
            override fun writeAtomic(key: String, bytes: ByteArray): Boolean { File(root, key).writeBytes(bytes); return true }
        }
        val fetched = hashMapOf<String, Int>()
        val cache = PersistentImageCache(store, { it.contentEquals(byteArrayOf(1)) }, { key ->
            fetched[key] = (fetched[key] ?: 0) + 1
            if (key == "b" && fetched[key] == 1) null else byteArrayOf(1)
        })
        val work = Work().apply { images = { ensureImageBatch(listOf("a", "b"), 1) { cache.ensure(it) } } }
        val f = Fixture(work)
        f.run.add(0, 0)
        f.drain()
        assertEquals(mapOf("a" to 1, "b" to 2), fetched)
        assertEquals(ImageBatchStats(2, hit = 1, stored = 1), f.result().batch)
        assertEquals(2, f.result().attempts)
        assertEquals(1, work.successes)
    }

    private fun persistenceFailure(writeFails: Boolean) = runBlocking {
        var visible = 0
        val store = object : ImageStore {
            override fun exists(key: String) = false
            override fun isUsable(key: String) = false // includes post-write validation
            override fun delete(key: String) = Unit
            override fun writeAtomic(key: String, bytes: ByteArray) = !writeFails
        }
        val cache = PersistentImageCache(store, { true }, { byteArrayOf(1) })
        val f = Fixture(Work().apply {
            images = { ensureImageBatch(listOf("a"), 1) { cache.ensure(it) { visible++ } } }
        })
        f.run.add(0, 0)
        f.drain()
        assertEquals(3, visible)
        assertEquals(0, f.work.successes)
        assertEquals(ImageBatchStats(1, failed = 1), f.result().batch)
        assertEquals(CacheBookImageOutcome.FAILED, f.result().outcome)
    }

    @Test fun `memory visible bytes with failed writes do not complete chapter`() = persistenceFailure(true)
    @Test fun `post write validation failure does not complete chapter`() = persistenceFailure(false)

    @Test fun `retry waits stay active and simultaneous chapters cannot remove model`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var waits = 0
        val f = Fixture(Work().apply { images = { ImageBatchStats(1, failed = 1) } }, retry = {
            if (++waits == 2) entered.complete(Unit)
            release.await()
        })
        f.run.add(0, 1)
        val first = f.run.schedule(this, EmptyCoroutineContext) { f.work }!!
        val second = f.run.schedule(this, EmptyCoroutineContext) { f.work }!!
        withTimeout(2000) { entered.await() }
        assertTrue(f.run.isRunning())
        assertEquals(2, f.run.activeCount())
        assertTrue(f.present)
        assertEquals(0, f.removals)
        assertEquals(CacheBookImageOutcome.RETRYING, f.result().outcome)
        release.complete(Unit)
        first.job!!.join(); second.job!!.join()
        assertEquals(2, f.run.waitCount())
        assertTrue(f.present)
        f.run.stop()
    }

    @Test fun `concurrent source throttle does not consume ordinary retry allowance`() = runBlocking {
        val work = Work(existing = false)
        work.fetch = { if (work.fetches < 4) throw ConcurrentException("busy", 1) else "real chapter" }
        val f = Fixture(work)
        f.run.add(0, 0)
        f.drain()
        assertEquals(4, work.fetches)
        assertEquals(listOf(0 to false, 0 to false, 0 to false), work.failures)
        assertEquals(1, work.successes)
    }

    @Test fun `text failure alone delivers text error and exhausts retries`() = runBlocking {
        val f = Fixture(Work(existing = false).apply { fetch = { error("text unavailable") } })
        f.run.add(0, 0)
        f.drain()
        assertEquals(3, f.work.deliveries.size)
        assertTrue(f.work.deliveries.all { it == "获取正文失败\ntext unavailable" })
        assertEquals(0, f.work.batches)
        assertNull(f.result().batch)
        assertEquals(CacheBookImageOutcome.TEXT_FAILED, f.result().outcome)
    }

    @Test fun `throwing image batch never converts fetched text into a text failure`() = runBlocking {
        val f = Fixture(Work(existing = false).apply { images = { error("images unavailable") } })
        f.run.add(0, 0)
        f.drain()
        assertEquals(listOf("real chapter"), f.work.deliveries)
        assertEquals(CacheBookImageOutcome.FAILED, f.result().outcome)
        assertNull(f.result().batch)
    }

    @Test fun `stop queued chapters records cancellation with no fabricated batch`() {
        val f = Fixture()
        f.run.add(0, 2)
        f.run.stop()
        assertFalse(f.run.isRunning())
        for (index in 0..2) {
            assertEquals(CacheBookImageOutcome.CANCELLED, f.result(index).outcome)
            assertEquals(0, f.result(index).attempts)
            assertNull(f.result(index).batch)
        }
    }

    private fun cancelPhase(phase: String) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun pause() { entered.complete(Unit); release.await() }
        val work = Work(existing = phase != "text")
        if (phase == "text") work.fetch = { pause(); "late text" }
        if (phase == "images") work.images = { pause(); ImageBatchStats(1, stored = 1) }
        if (phase == "retry") work.images = { ImageBatchStats(1, failed = 1) }
        val f = Fixture(work, retry = { if (phase == "retry") pause() })
        f.run.add(0, 0)
        val token = f.run.schedule(this, EmptyCoroutineContext) { work }!!
        withTimeout(2000) { entered.await() }
        val previous = f.result().batch
        val previousTime = f.result().batchAt
        f.run.stop()
        token.job!!.join()
        assertFalse(f.run.isRunning())
        assertEquals(CacheBookImageOutcome.CANCELLED, f.result().outcome)
        assertEquals(previous, f.result().batch)
        assertEquals(previousTime, f.result().batchAt)
        assertEquals(0, work.successes)
        assertTrue(work.deliveries.isEmpty())
        assertEquals(if (phase == "retry") 1 else 0, work.failures.size)
    }

    @Test fun `cancel text retrieval does not report ordinary failure`() = cancelPhase("text")
    @Test fun `cancel image batch retains no incomplete stats`() = cancelPhase("images")
    @Test fun `cancel retry delay keeps prior complete batch separately`() = cancelPhase("retry")

    @Test fun `batch throwing cancellation stops without errors or retries`() = runBlocking {
        val f = Fixture(Work().apply { images = { throw CancellationException("cancel") } })
        f.run.add(0, 0)
        f.drain()
        assertEquals(1, f.work.batches)
        assertTrue(f.work.failures.isEmpty())
        assertEquals(CacheBookImageOutcome.CANCELLED, f.result().outcome)
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun flush() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    @Test fun `cancel between registration and execution does not need block finally`() = runBlocking {
        val dispatcher = PausedDispatcher()
        val f = Fixture()
        f.run.add(0, 0)
        f.run.schedule(this, dispatcher) { f.work }
        f.run.stop()
        dispatcher.flush()
        assertEquals(0, f.work.reads)
        assertFalse(f.run.isRunning())
        assertEquals(CacheBookImageOutcome.CANCELLED, f.result().outcome)
    }

    private fun staleCompletion(fail: Boolean, phase: String) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = Work(existing = phase != "text")
        suspend fun late() { withContext(NonCancellable) { entered.complete(Unit); release.await() }; if (fail) error("late failure") }
        if (phase == "text") old.fetch = { late(); "old text" }
        else old.images = { late(); ImageBatchStats(1, stored = 1) }
        val f = Fixture(old)
        f.run.add(0, 0)
        val token = f.run.schedule(this, EmptyCoroutineContext) { old }!!
        entered.await()
        f.run.stop()
        val next = Work(existing = false)
        f.run.add(0, 0)
        val nextToken = f.run.schedule(this, EmptyCoroutineContext) { next }!!
        nextToken.job!!.join()
        val newResult = f.result()
        release.complete(Unit)
        token.job!!.join()
        assertEquals(newResult, f.result())
        assertEquals(listOf("real chapter"), next.deliveries)
        assertEquals(1, next.successes)
        assertEquals(0, old.successes)
        assertTrue(old.deliveries.isEmpty())
        assertTrue(old.failures.isEmpty())
        // Independently repeat all old callback kinds and cleanup orders.
        for (kind in listOf("success", "failure", "cancel", "retry", "deliver")) {
            assertFalse(kind, f.run.commit(token) { fail("stale $kind callback accepted") })
        }
        f.run.finish(token, null)
        f.run.finish(token, CancellationException())
        assertEquals(newResult, f.result())
        assertFalse(f.run.isRunning())
    }

    @Test fun `old text success after restart cannot deliver into new run`() = staleCompletion(false, "text")
    @Test fun `old text failure after restart cannot report or requeue`() = staleCompletion(true, "text")
    @Test fun `old image success and cancel finally after restart are ignored`() = staleCompletion(false, "images")
    @Test fun `old image failure after restart cannot replace latest result`() = staleCompletion(true, "images")

    @Test fun `old cleanup cannot remove a still running replacement`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture(Work().apply { images = { entered.complete(Unit); release.await(); ImageBatchStats(1, hit = 1) } })
        f.run.add(0, 0)
        val old = f.run.schedule(this, EmptyCoroutineContext) { f.work }!!
        entered.await()
        f.run.stop()
        f.run.add(0, 0)
        val newRun = f.result().runId
        f.run.finish(old, null)
        f.run.finish(old, CancellationException())
        assertTrue(f.present)
        assertEquals(1, f.run.waitCount())
        assertEquals(newRun, f.result().runId)
        f.run.stop()
    }

    private fun cancelPreparation(phase: String, restart: Boolean = false) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture()
        var writes = 0
        var failed = 0
        var details = 0
        var toc = 0
        val old = f.run.prepare(this, EmptyCoroutineContext, 0, -1, load = { token ->
            details++
            if (phase == "details") withContext(NonCancellable) { entered.complete(Unit); release.await() }
            f.run.withPreparation(token) { Unit }
            toc++
            if (phase == "toc") withContext(NonCancellable) { entered.complete(Unit); release.await() }
            f.run.withPreparation(token) { writes++ }
            1
        }, failed = { failed++ })
        entered.await()
        assertTrue(f.run.isPreparing())
        f.run.stop()
        if (restart) f.run.prepare(this, EmptyCoroutineContext, 5, -1, { 5 }, { fail("new preparation failed") }).join()
        release.complete(Unit)
        old.join()
        assertEquals(0, writes)
        assertEquals(0, failed)
        assertEquals(if (phase == "details") 0 else 1, toc)
        assertEquals(if (restart) 1 else 0, f.run.waitCount())
        assertEquals(restart, f.present)
        if (restart) { assertEquals(CacheBookImageOutcome.QUEUED, f.result(5).outcome); f.run.stop() }
    }

    @Test fun `cancel detail preparation prevents toc and enqueue`() = cancelPreparation("details")
    @Test fun `cancel toc preparation prevents persistence and enqueue`() = cancelPreparation("toc")
    @Test fun `old preparation and finally cannot affect restarted book`() = cancelPreparation("toc", true)

    @Test fun `multiple ranges join one preparation and enqueue after completion`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture()
        val job = f.run.prepare(this, EmptyCoroutineContext, 0, 1, { entered.complete(Unit); release.await(); 5 }, { fail() })
        entered.await()
        val same = f.run.prepare(this, EmptyCoroutineContext, 4, -1, { fail("must reuse prep"); 5 }, { fail() })
        assertSame(job, same)
        assertEquals(0, f.run.waitCount())
        release.complete(Unit)
        job.join()
        assertEquals(4, f.run.waitCount())
        f.run.stop()
    }

    @Test fun `one book preparation cancellation does not stop another`() = runBlocking {
        val a = Fixture(book = "a")
        val b = Fixture(book = "b")
        val release = CompletableDeferred<Unit>()
        a.run.prepare(this, EmptyCoroutineContext, 0, 0, { release.await(); 0 }, { fail() })
        b.run.prepare(this, EmptyCoroutineContext, 0, 0, { release.await(); 0 }, { fail() })
        yield()
        a.run.stop()
        release.complete(Unit)
        yield()
        assertFalse(a.run.isRunning())
        assertEquals(1, b.run.waitCount())
        b.run.stop()
    }

    @Test fun `service ticket cancellation before model lookup prevents late creation`() = runBlocking {
        val tickets = CacheBookPreparations()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var created = 0
        tickets.launch("book", this, EmptyCoroutineContext) { ticket ->
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            tickets.withCurrent(ticket) { created++ }
        }
        entered.await()
        tickets.stop("book")
        tickets.launch("book", this, EmptyCoroutineContext) { ticket -> tickets.withCurrent(ticket) { created++ } }
        yield()
        release.complete(Unit)
        yield()
        assertEquals(1, created)
        assertFalse(tickets.isRunning())
    }

    @Test fun `global ticket stop invalidates every book before jobs complete`() = runBlocking {
        val tickets = CacheBookPreparations()
        val dispatcher = PausedDispatcher()
        var starts = 0
        for (book in listOf("a", "b")) tickets.launch(book, this, dispatcher) { starts++ }
        assertTrue(tickets.isRunning())
        tickets.stopAll()
        dispatcher.flush()
        assertEquals(0, starts)
        assertFalse(tickets.isRunning())
    }

    @Test fun `results survive completion and stop but are bounded and unknown before first run`() = runBlocking {
        val results = CacheBookImageResults(2)
        assertNull(results.get("a", 0))
        for (book in listOf("a", "b", "c")) {
            val f = Fixture(results = results, book = book)
            f.run.add(0, 0)
            f.drain()
            f.run.stop()
            assertEquals(CacheBookImageOutcome.SUCCESS, f.result().outcome)
        }
        assertNull(results.get("a", 0))
        assertEquals(1, results.forBook("b").size)
        assertEquals(1, results.forBook("c").size)
    }

    @Test fun `new model run replaces old result and rejects stale writes`() = runBlocking {
        val results = CacheBookImageResults()
        val old = Fixture(results = results)
        old.run.add(0, 0)
        old.drain()
        val previous = old.result()
        val next = Fixture(results = results)
        next.run.add(0, 0)
        val queued = next.result()
        assertTrue(queued.runId > previous.runId)
        assertNull(queued.batch)
        results.put(previous)
        assertEquals(queued, next.result())
        next.drain()
        assertEquals(CacheBookImageOutcome.SUCCESS, next.result().outcome)
    }

    @Test fun `previous attempt callbacks in same run cannot remove retry attempt`() = runBlocking {
        val f = Fixture(Work().apply { images = { ImageBatchStats(1, failed = 1) } })
        f.run.add(0, 0)
        val first = f.run.schedule(this, EmptyCoroutineContext) { f.work }!!
        first.job!!.join()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.work.images = { entered.complete(Unit); release.await(); ImageBatchStats(1, hit = 1) }
        val second = f.run.schedule(this, EmptyCoroutineContext) { f.work }!!
        entered.await()
        assertEquals(first.runId, second.runId)
        assertNotEquals(first.id, second.id)
        assertFalse(f.run.commit(first) { fail("previous attempt accepted") })
        f.run.finish(first, null)
        f.run.finish(first, CancellationException())
        assertEquals(1, f.run.activeCount())
        assertTrue(f.present)
        release.complete(Unit)
        second.job!!.join()
        assertEquals(CacheBookImageOutcome.SUCCESS, f.result().outcome)
        assertEquals(2, f.result().attempts)
    }

    @Test fun `one completed chapter cannot remove another waiting retry`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture(retry = { waiting.complete(Unit); release.await() })
        val failure = Work().apply { images = { ImageBatchStats(1, failed = 1) } }
        f.run.add(0, 1)
        val a = f.run.schedule(this, EmptyCoroutineContext) { failure }!!
        waiting.await()
        val b = f.run.schedule(this, EmptyCoroutineContext) { f.work }!!
        b.job!!.join()
        assertEquals(1, f.run.activeCount())
        assertEquals(0, f.run.waitCount())
        assertTrue(f.present)
        assertTrue(f.run.isRunning())
        assertEquals(CacheBookImageOutcome.SUCCESS, f.result(1).outcome)
        f.run.stop()
        a.job!!.join()
    }

    @Test fun `foreground text error consumes same three attempt budget`() = runBlocking {
        val f = Fixture(Work(isManga = false, existing = false).apply { fetch = { error("text failed") } })
        f.run.retryFromReading(0, 1)
        f.drain()
        assertEquals(2, f.work.fetches)
        assertEquals(listOf(2 to false, 3 to true), f.work.failures)
    }

    @Test fun `new book lookup lease keeps model owned until preparation is installed`() = runBlocking {
        val tickets = CacheBookPreparations()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var owned = true
        val run = CacheBookRun("book", { true }, CacheBookImageResults(),
            onIdle = { if (!tickets.contains("book")) owned = false })
        tickets.launch("book", this, EmptyCoroutineContext) { ticket ->
            entered.complete(Unit)
            release.await()
            tickets.withCurrent(ticket) { run.prepare(this, EmptyCoroutineContext, 0, 0, { 0 }, { fail() }) }.join()
        }
        entered.await()
        run.schedule(this, EmptyCoroutineContext) { null }
        assertTrue(owned)
        assertTrue(tickets.contains("book"))
        release.complete(Unit)
        yield(); yield()
        assertEquals(1, run.waitCount())
        tickets.stopAll()
        run.stop()
    }

    @Test fun `stop and callback side effect are serialized across threads`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val releaseWork = CompletableDeferred<Unit>()
        val f = Fixture(Work().apply { images = { entered.complete(Unit); releaseWork.await(); ImageBatchStats(1, hit = 1) } })
        f.run.add(0, 0)
        val token = f.run.schedule(this, EmptyCoroutineContext) { f.work }!!
        entered.await()
        val inCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        var deliveries = 0
        val callback = launch(Dispatchers.Default) {
            f.run.commit(token) {
                inCommit.countDown()
                check(releaseCommit.await(2, TimeUnit.SECONDS))
                deliveries++
            }
        }
        assertTrue(inCommit.await(2, TimeUnit.SECONDS))
        val stopStarted = CompletableDeferred<Unit>()
        val stopping = launch(Dispatchers.Default) { stopStarted.complete(Unit); f.run.stop() }
        stopStarted.await()
        assertFalse(stopping.isCompleted)
        releaseCommit.countDown()
        callback.join(); stopping.join(); token.job!!.join()
        assertEquals(1, deliveries) // committed before stop's linearization point
        assertFalse(f.run.commit(token) { deliveries++ })
        assertEquals(1, deliveries)
        assertEquals(CacheBookImageOutcome.CANCELLED, f.result().outcome)
    }

    @Test fun `cancel parent scope cleans up active attempt without explicit model stop`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture(Work().apply { images = { entered.complete(Unit); release.await(); ImageBatchStats(1, hit = 1) } })
        f.run.add(0, 0)
        val parent = launch { coroutineScope { f.run.schedule(this, EmptyCoroutineContext) { f.work } } }
        entered.await()
        parent.cancel()
        parent.join()
        assertFalse(f.run.isRunning())
        assertFalse(f.present)
        assertTrue(f.work.failures.isEmpty())
        assertEquals(CacheBookImageOutcome.CANCELLED, f.result().outcome)
    }

    @Test fun `cancelled old worker cannot dequeue a restarted run`() = runBlocking {
        val f = Fixture()
        f.run.add(0, 0)
        f.run.stop()
        f.run.add(0, 0)
        val queued = f.result()
        val oldParent = Job().apply { cancel() }
        assertNull(f.run.schedule(CoroutineScope(oldParent), EmptyCoroutineContext) { f.work })
        assertEquals(1, f.run.waitCount())
        assertEquals(queued, f.result())
        f.drain()
        assertEquals(CacheBookImageOutcome.SUCCESS, f.result().outcome)
    }
}
