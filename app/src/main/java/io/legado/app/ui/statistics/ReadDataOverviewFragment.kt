package io.legado.app.ui.statistics

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.help.book.ReadRecordDetailHelper
import io.legado.app.help.book.buildReadingStatistics
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.toReadDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 阅读数据中心 - 总览。
 *
 * 口径说明（不伪造数据）：
 * - 累计时长/书籍数/在读/已读完/最近阅读：来自 readRecord 累计表 + books 当前状态（历史真实数据）；
 * - 本周/本月/阅读天数/日均/近 7 天趋势：来自 readRecordDetail 每日明细（新版本起开始积累，
 *   未积累前显示为 0，不回填历史）。
 */
class ReadDataOverviewFragment : BaseFragment(R.layout.fragment_read_data_overview) {

    private val rankAdapter by lazy { ReadDataRankAdapter(requireContext()) }
    private val dayFormat = DateTimeFormatter.ofPattern("MM-dd")

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.rv_recent).adapter = rankAdapter
    }

    override fun onResume() {
        super.onResume()
        loadStatistics()
    }

    fun reload() {
        if (isAdded) {
            loadStatistics()
        }
    }

    private fun loadStatistics() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                val weekStart = ReadRecordDetailHelper.weekStartOf(today)
                val weekEnd = weekStart.plusWeeks(1)
                val monthStart = today.withDayOfMonth(1)
                val monthEnd = monthStart.plusMonths(1)
                val detailDao = appDb.readRecordDetailDao
                // 聚合查询替代全表加载：天数与总时长只取汇总值，避免随历史增长的全表扫描
                val daysRead = detailDao.distinctDateCount()
                val detailSum = detailDao.totalSum ?: 0L
                val trend = (6 downTo 0).map { offset ->
                    val date = today.minusDays(offset.toLong())
                    val next = date.plusDays(1)
                    Triple(
                        date,
                        detailDao.sumRange(date.toString(), next.toString()) ?: 0L,
                        detailDao.getByDateRange(date.toString(), next.toString())
                            .map { it.bookName }.distinct().size
                    )
                }
                val statistics = buildReadingStatistics(
                    books = appDb.bookDao.all,
                    recordShows = appDb.readRecordDao.allShow,
                    recordRows = appDb.readRecordDao.all,
                    totalReadTime = appDb.readRecordDao.allTime
                )
                val weekTime = detailDao.sumRange(weekStart.toString(), weekEnd.toString()) ?: 0L
                val monthTime = detailDao.sumRange(monthStart.toString(), monthEnd.toString()) ?: 0L
                val recent = statistics.recentRead.take(10).map {
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
                LoadResult(statistics, weekTime, monthTime, daysRead, detailSum, trend, recent)
            }
            bindOverview(result)
        }
    }

    private data class LoadResult(
        val statistics: io.legado.app.help.book.ReadingStatistics,
        val weekTime: Long,
        val monthTime: Long,
        val daysRead: Int,
        val detailSum: Long,
        val trend: List<Triple<LocalDate, Long, Int>>,
        val recent: List<ReadDataBookShow>
    )

    private fun bindOverview(result: LoadResult) {
        val rootView = view ?: return
        val statistics = result.statistics
        rootView.findViewById<TextView>(R.id.tv_total_time).text =
            statistics.totalReadTime.toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_total_books).text = statistics.totalBookCount.toString()
        rootView.findViewById<TextView>(R.id.tv_reading_count).text = statistics.readingCount.toString()
        rootView.findViewById<TextView>(R.id.tv_finished_count).text = statistics.finishedCount.toString()
        rootView.findViewById<TextView>(R.id.tv_week_time).text = result.weekTime.toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_month_time).text = result.monthTime.toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_days_read).text = result.daysRead.toString()
        rootView.findViewById<TextView>(R.id.tv_daily_avg).text =
            (result.detailSum / result.daysRead.coerceAtLeast(1)).toReadDuration()
        rootView.findViewById<TextView>(R.id.tv_duplicate_warning).isVisible = statistics.hasDuplicateRecords

        val maxTrend = result.trend.maxOfOrNull { it.second } ?: 0L
        val trendContainer = rootView.findViewById<LinearLayout>(R.id.ll_trend)
        trendContainer.removeAllViews()
        val inflater = layoutInflater
        for ((date, time, bookCount) in result.trend) {
            val row = inflater.inflate(R.layout.item_read_data_day, trendContainer, false)
            row.findViewById<TextView>(R.id.tv_left).text = date.format(dayFormat)
            row.findViewById<TextView>(R.id.tv_right).text =
                if (bookCount > 0) {
                    "${time.toReadDuration()} · ${getString(R.string.read_data_day_books, bookCount)}"
                } else {
                    time.toReadDuration()
                }
            val bar = row.findViewById<View>(R.id.view_bar)
            val lp = bar.layoutParams
            lp.width = if (maxTrend > 0 && time > 0) {
                (trendContainer.resources.displayMetrics.widthPixels / 3 * (time.toFloat() / maxTrend)).toInt()
            } else {
                0
            }
            bar.layoutParams = lp
            bar.isVisible = time > 0
            trendContainer.addView(row)
        }

        val recentList = result.recent
        rootView.findViewById<TextView>(R.id.tv_empty).isVisible =
            statistics.items.isEmpty() && result.detailSum <= 0L
        rankAdapter.setItems(recentList)
    }

}
