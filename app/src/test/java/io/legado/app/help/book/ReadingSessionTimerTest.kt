package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSessionTimerTest {
    private val windows = mutableListOf<Triple<String, Long, Long>>()
    private val timer = ReadingSessionTimer<String>({}, { book, start, end ->
        windows += Triple(book, start, end)
    })

    @Test fun repeatedResumeDoesNotDiscardUnsettledTime() {
        timer.bind("A", 1_000)
        timer.resume(1_000)
        timer.resume(31_000)
        timer.pause(61_000)
        assertEquals(listOf(Triple("A", 1_000L, 61_000L)), windows)
    }

    @Test fun noBookDoesNotInventReadingTimeDuringLoading() {
        timer.resume(1_000)
        timer.checkpoint(16_000)
        timer.bind("A", 21_000)
        timer.pause(61_000)
        assertEquals(listOf(Triple("A", 21_000L, 61_000L)), windows)
    }

    @Test fun switchingIntentClosesOldBookBeforeLoadingNewBook() {
        timer.bind("A", 1_000)
        timer.resume(1_000)
        timer.unbind(61_000)
        timer.checkpoint(76_000)
        timer.bind("B", 91_000)
        timer.pause(181_000)
        assertEquals(listOf(Triple("A", 1_000L, 61_000L), Triple("B", 91_000L, 181_000L)), windows)
    }

    @Test fun repeatedBindingDoesNotResetUnsettledTime() {
        timer.bind("A", 1_000)
        timer.resume(1_000)
        timer.bind("A", 31_000)
        timer.pause(61_000)
        assertEquals(60_000L, windows.sumOf { it.third - it.second })
    }

    @Test fun staleQueuedTimestampCannotMoveWindowBackwards() {
        timer.bind("A", 10_000)
        timer.resume(10_000)
        timer.checkpoint(40_000)
        timer.checkpoint(20_000)
        timer.pause(70_000)
        assertEquals(60_000L, windows.sumOf { it.third - it.second })
    }

    @Test fun clearReanchorsWithoutReplayingClearedWindow() {
        timer.bind("A", 1_000)
        timer.resume(1_000)
        timer.reanchor(41_000)
        timer.pause(61_000)
        assertEquals(listOf(Triple("A", 41_000L, 61_000L)), windows)
    }

    @Test fun backgroundReadingRequiresExplicitAloudCheckpoint() {
        timer.bind("A", 1_000)
        timer.checkpoint(31_000)
        assertTrue(windows.isEmpty())
        timer.checkpoint(61_000, includeBackground = true)
        assertEquals(listOf(Triple("A", 1_000L, 61_000L)), windows)
    }

    @Test fun pauseThenImmediateResumeDoesNotReplayWindow() {
        timer.bind("A", 1_000)
        timer.resume(1_000)
        timer.pause(31_000)
        timer.resume(31_000)
        timer.pause(61_000)
        assertEquals(60_000L, windows.sumOf { it.third - it.second })
    }

    @Test fun queuedLifecycleAndBookChangesCannotUndoClearBoundary() {
        timer.reanchor(150_000)
        timer.resume(110_000)
        timer.bind("A", 120_000)
        timer.unbind(130_000)
        timer.bind("B", 140_000)
        timer.pause(180_000)
        assertEquals(listOf(Triple("B", 150_000L, 180_000L)), windows)
    }
}
