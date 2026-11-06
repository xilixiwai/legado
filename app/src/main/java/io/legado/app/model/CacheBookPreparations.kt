package io.legado.app.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** Covers the service's asynchronous DB lookup before a CacheBookModel can own preparation. */
internal class CacheBookPreparations {
    internal class Ticket(val bookUrl: String) {
        var job: Job? = null
    }

    private val tickets: MutableSet<Ticket> = Collections.newSetFromMap(ConcurrentHashMap())

    fun isRunning() = tickets.isNotEmpty()
    // No monitor acquisition: model completion also checks this while holding its run lock.
    fun contains(bookUrl: String) = tickets.any { it.bookUrl == bookUrl }

    @Synchronized
    fun launch(
        bookUrl: String,
        scope: CoroutineScope,
        context: CoroutineContext,
        block: suspend (Ticket) -> Unit,
    ) {
        val ticket = Ticket(bookUrl)
        tickets.add(ticket)
        val job = scope.launch(context, start = CoroutineStart.LAZY) { block(ticket) }
        ticket.job = job
        job.invokeOnCompletion { synchronized(this) { tickets.remove(ticket) } }
        job.start()
    }

    @Synchronized
    fun <T> withCurrent(ticket: Ticket, block: () -> T): T {
        if (ticket !in tickets || ticket.job?.isCancelled == true) {
            throw CancellationException("CacheBook service request superseded")
        }
        return block()
    }

    fun stop(bookUrl: String) = cancel { it.bookUrl == bookUrl }
    fun stopAll() = cancel { true }

    private fun cancel(matches: (Ticket) -> Boolean) {
        val jobs = synchronized(this) {
            tickets.filter(matches).also { tickets.removeAll(it.toSet()) }.mapNotNull { it.job }
        }
        jobs.forEach { it.cancel() }
    }
}
