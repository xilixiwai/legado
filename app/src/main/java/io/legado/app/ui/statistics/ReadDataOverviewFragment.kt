package io.legado.app.ui.statistics

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.help.book.ReadRecordDetailHelper
import io.legado.app.help.book.ReadingStatistics
import io.legado.app.help.book.buildReadingStatistics
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.toReadDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.Callable

/**
 * Read-only overview of persisted statistics. Lifetime totals keep the existing cumulative-table
 * scope; daily/weekly/monthly values use the daily detail table without backfilling older totals.
 */
class ReadDataOverviewFragment : BaseFragment(R.layout.fragment_read_data_overview) {

    private val rankAdapter by lazy { ReadDataRankAdapter(requireContext()) }
    private var loadJob: Job? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.rv_recent).adapter = rankAdapter
        val context = requireContext()
        val surface = ColorUtils.blendARGB(context.backgroundColor, context.primaryTextColor, 0.04f)
        val outline = ColorUtils.blendARGB(context.backgroundColor, context.primaryTextColor, 0.12f)
        val density = resources.displayMetrics.density
        listOf(R.id.card_total, R.id.card_today, R.id.card_books, R.id.card_days,
            R.id.card_trend, R.id.card_periods, R.id.card_recent).forEach { id ->
            view.findViewById<View>(id).background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(if (id == R.id.card_total) {
                    ColorUtils.blendARGB(context.backgroundColor, context.accentColor, 0.12f)
                } else surface)
                setStroke(density.toInt().coerceAtLeast(1), outline)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadStatistics()
    }

    fun reload() {
        if (view != null) loadStatistics()
    }

    private fun loadStatistics() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ReadRecordDetailHelper.awaitPendingWrites()
                // All cards describe the same committed database snapshot, even during a settlement.
                appDb.runInTransaction(Callable {
                    val today = LocalDate.now()
                    val weekStart = ReadRecordDetailHelper.weekStartOf(today)
                    val monthStart = today.withDayOfMonth(1)
                    val detailDao = appDb.readRecordDetailDao
                    val daysRead = detailDao.distinctDateCount()
                    val detailSum = detailDao.totalSum ?: 0L
                    val trend = (6 downTo 0).map { offset ->
                        val date = today.minusDays(offset.toLong())
                        ReadingTrendDay(date,
                            detailDao.sumRange(date.toString(), date.plusDays(1).toString()) ?: 0L)
                    }
                    val statistics = buildReadingStatistics(
                        books = appDb.bookDao.all,
                        recordShows = appDb.readRecordDao.allShow,
                        recordRows = appDb.readRecordDao.all,
                        totalReadTime = appDb.readRecordDao.allTime
                    )
                    val weekTime = detailDao.sumRange(weekStart.toString(), weekStart.plusWeeks(1).toString()) ?: 0L
                    val monthTime = detailDao.sumRange(monthStart.toString(), monthStart.plusMonths(1).toString()) ?: 0L
                    val recent = statistics.recentRead.take(3).map {
                        ReadDataBookShow(
                            bookName = it.bookName,
                            bookAuthor = it.author,
                            readTime = it.readTime,
                            lastRead = it.lastRead,
                            bookType = 0,
                            coverUrl = it.coverUrl,
                            origin = it.origin,
                            inBookshelf = it.inBookshelf
                        )
                    }
                    LoadResult(statistics, weekTime, monthTime, daysRead, detailSum, trend, recent, today)
                })
            }
            bindOverview(result)
        }
    }

    private data class LoadResult(
        val statistics: ReadingStatistics,
        val weekTime: Long,
        val monthTime: Long,
        val daysRead: Int,
        val detailSum: Long,
        val trend: List<ReadingTrendDay>,
        val recent: List<ReadDataBookShow>,
        val today: LocalDate
    )

    private fun bindOverview(result: LoadResult) {
        val rootView = view ?: return
        val statistics = result.statistics
        rootView.findViewById<TextView>(R.id.tv_total_time).text =
            SpannableString(statistics.totalReadTime.toReadDuration()).apply {
                // Keep the exact saved duration; quieter unit labels emphasize the actual numbers.
                Regex("[^0-9]+").findAll(this).forEach { unit ->
                    setSpan(RelativeSizeSpan(0.6f), unit.range.first, unit.range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        rootView.findViewById<TextView>(R.id.tv_total_books).text = statistics.totalBookCount.toString()
        rootView.findViewById<TextView>(R.id.tv_today_time).text =
            (result.trend.lastOrNull()?.readTime ?: 0L).toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_week_time).text = result.weekTime.toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_month_time).text = result.monthTime.toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_days_read).text = result.daysRead.toString()
        rootView.findViewById<TextView>(R.id.tv_daily_avg).text =
            dailyReadingAverage(result.detailSum, result.daysRead).toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_duplicate_warning).isVisible = statistics.hasDuplicateRecords

        val maximum = result.trend.maxOfOrNull { it.readTime } ?: 0L
        rootView.findViewById<TextView>(R.id.tv_trend_scale).text = if (maximum == 0L) {
            getString(R.string.read_overview_trend_empty)
        } else {
            getString(R.string.read_overview_trend_scale, maximum.toReadDuration())
        }
        rootView.findViewById<ReadingTrendView>(R.id.reading_trend).setDays(result.trend, result.today) { day ->
            rootView.findViewById<TextView>(R.id.tv_trend_selected).text =
                getString(R.string.read_overview_trend_selected, day.date.toString(), day.readTime.toReadDuration())
        }
        rootView.findViewById<TextView>(R.id.tv_empty).isVisible = result.recent.isEmpty()
        rootView.findViewById<RecyclerView>(R.id.rv_recent).isVisible = result.recent.isNotEmpty()
        rankAdapter.setItems(result.recent)
    }
}
