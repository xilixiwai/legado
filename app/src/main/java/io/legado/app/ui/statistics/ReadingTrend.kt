package io.legado.app.ui.statistics

import java.time.LocalDate

/** Display-only daily values; durations remain in milliseconds throughout. */
data class ReadingTrendDay(val date: LocalDate, val readTime: Long)

internal fun readingTrendRatios(days: List<ReadingTrendDay>): List<Float> {
    val maximum = days.maxOfOrNull { it.readTime } ?: 0L
    if (maximum <= 0L) return days.map { 0f }
    return days.map { (it.readTime.coerceAtLeast(0L).toDouble() / maximum).toFloat() }
}

/** Keep the existing denominator: dates present in the daily detail table. */
internal fun dailyReadingAverage(total: Long, recordedDays: Int): Long =
    total / recordedDays.coerceAtLeast(1)
