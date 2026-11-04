package io.legado.app.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReadingTrendTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun proportionalBarsKeepSecondsAndDoNotInventMinimumTime() {
        val ratios = readingTrendRatios(listOf(0L, 1_000L, 60_000L, 90_000L).map {
            ReadingTrendDay(today, it)
        })
        assertEquals(0f, ratios[0], 0f)
        assertEquals(1f / 90f, ratios[1], 0.00001f)
        assertEquals(2f / 3f, ratios[2], 0.00001f)
        assertEquals(1f, ratios[3], 0f)
    }

    @Test fun sevenEmptyDaysStayEmpty() {
        val ratios = readingTrendRatios((6 downTo 0).map {
            ReadingTrendDay(today.minusDays(it.toLong()), 0L)
        })
        assertEquals(7, ratios.size)
        assertTrue(ratios.all { it == 0f })
        assertTrue(readingTrendRatios(emptyList()).isEmpty())
    }

    @Test fun averageUsesRecordedDaysAndRetainsMilliseconds() {
        assertEquals(75_000L, dailyReadingAverage(150_000L, 2))
        assertEquals(1_500L, dailyReadingAverage(3_000L, 2))
        assertEquals(0L, dailyReadingAverage(0L, 0))
    }

    @Test fun largeDurationsDoNotOverflowChartScaling() {
        val ratios = readingTrendRatios(listOf(
            ReadingTrendDay(today.minusDays(1), Long.MAX_VALUE / 2),
            ReadingTrendDay(today, Long.MAX_VALUE)
        ))
        assertEquals(0.5f, ratios[0], 0.00001f)
        assertEquals(1f, ratios[1], 0f)
    }
}
