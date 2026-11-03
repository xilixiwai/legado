package io.legado.app.ui.statistics

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordDetail
import io.legado.app.databinding.ItemReadDataCalendarDayBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.gone
import io.legado.app.utils.toReadDuration
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 阅读数据中心 - 日历。
 * 月历按周一起始；每日时长/书籍数来自每日明细（新版本起积累），无数据日不伪造。
 */
class ReadDataCalendarFragment : BaseFragment(R.layout.fragment_read_data_calendar) {

    private var currentMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate? = LocalDate.now()
    private val dayAdapter by lazy {
        CalendarDayGridAdapter(requireContext()) { cell ->
            cell.date ?: return@CalendarDayGridAdapter
            selectedDate = cell.date
            loadMonth()
        }
    }
    private val detailAdapter by lazy { ReadDataRankAdapter(requireContext()) }
    private val monthTitleFormat = DateTimeFormatter.ofPattern("yyyy年MM月")
    private val dayTitleFormat = DateTimeFormatter.ofPattern("M月d日")

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.rv_calendar).run {
            layoutManager = GridLayoutManager(requireContext(), 7)
            adapter = dayAdapter
        }
        view.findViewById<RecyclerView>(R.id.rv_day_detail).adapter = detailAdapter
        view.findViewById<View>(R.id.tv_month_prev).setOnClickListener {
            currentMonth = currentMonth.minusMonths(1)
            loadMonth()
        }
        view.findViewById<View>(R.id.tv_month_next).setOnClickListener {
            currentMonth = currentMonth.plusMonths(1)
            loadMonth()
        }
    }

    override fun onResume() {
        super.onResume()
        loadMonth()
    }

    fun reload() {
        if (isAdded) {
            loadMonth()
        }
    }

    private fun loadMonth() {
        lifecycleScope.launch {
            val monthStart = currentMonth.atDay(1)
            val monthEnd = currentMonth.plusMonths(1).atDay(1)
            val result = withContext(Dispatchers.IO) {
                val dao = appDb.readRecordDetailDao
                val rows = dao.getByDateRange(monthStart.toString(), monthEnd.toString())
                val byDate = rows.groupBy { it.date }.mapValues { (_, list) ->
                    DayData(list.sumOf { it.readTime }, list.map { it.bookName }.distinct().size)
                }
                val since = dao.firstDate()
                val detailDate = selectedDate?.takeIf {
                    !it.isBefore(monthStart) && it.isBefore(monthEnd)
                } ?: LocalDate.now().takeIf {
                    !it.isBefore(monthStart) && it.isBefore(monthEnd)
                }
                val detailRows = detailDate?.let {
                    dao.getByDateRange(it.toString(), it.plusDays(1).toString())
                }.orEmpty()
                CalendarLoad(byDate, since, detailDate, detailRows)
            }
            bindMonth(result)
        }
    }

    private data class DayData(val readTime: Long, val bookCount: Int)

    private data class CalendarLoad(
        val byDate: Map<String, DayData>,
        val since: String?,
        val selectedDate: LocalDate?,
        val detailRows: List<ReadRecordDetail>
    )

    private fun bindMonth(result: CalendarLoad) {
        val rootView = view ?: return
        val tvMonthTitle = rootView.findViewById<TextView>(R.id.tv_month_title)
        tvMonthTitle.text = currentMonth.format(monthTitleFormat)
        rootView.findViewById<TextView>(R.id.tv_since).text = result.since?.let {
            getString(R.string.read_data_since, it)
        } ?: ""

        val daysInMonth = currentMonth.lengthOfMonth()
        // 周一为一周起点：MONDAY = 1 → 前导空白 = dayOfWeek - 1
        val leading = currentMonth.atDay(1).dayOfWeek.value - 1
        val today = LocalDate.now()
        val cells = ArrayList<CalendarDayCell>(leading + daysInMonth)
        repeat(leading) { cells.add(CalendarDayCell.empty()) }
        for (day in 1..daysInMonth) {
            val date = currentMonth.atDay(day)
            val data = result.byDate[date.toString()]
            cells.add(
                CalendarDayCell(
                    date = date,
                    dayNum = day.toString(),
                    readTime = data?.readTime ?: 0L,
                    bookCount = data?.bookCount ?: 0,
                    selected = date == result.selectedDate,
                    isToday = date == today
                )
            )
        }
        dayAdapter.setItems(cells)
        rootView.findViewById<TextView>(R.id.tv_empty).isVisible = result.byDate.isEmpty()

        // 选中日明细
        val tvSummary = rootView.findViewById<TextView>(R.id.tv_day_summary)
        val rvDetail = rootView.findViewById<RecyclerView>(R.id.rv_day_detail)
        val detailRows = result.detailRows
        if (result.selectedDate == null) {
            tvSummary.gone()
            rvDetail.gone()
        } else {
            tvSummary.visible()
            rvDetail.visible()
            val sum = detailRows.sumOf { it.readTime }
            val bookCount = detailRows.map { it.bookName }.distinct().size
            tvSummary.text = getString(
                R.string.read_data_day_summary,
                result.selectedDate!!.format(dayTitleFormat),
                sum.toReadDuration(),
                bookCount
            )
        }
        rvDetail.isVisible = detailRows.isNotEmpty()
        if (detailRows.isEmpty()) {
            detailAdapter.setItems(emptyList())
        } else {
            lifecycleScope.launch {
                val shows = withContext(Dispatchers.IO) {
                    detailRows.groupBy { it.bookName to it.bookAuthor }.map { (_, list) ->
                        ReadDataBookShow.fromRank(
                            bookName = list[0].bookName,
                            bookAuthor = list[0].bookAuthor,
                            readTime = list.sumOf { it.readTime },
                            lastReadTime = list.maxOf { it.lastReadTime },
                            bookType = list.maxOf { it.bookType }
                        )
                    }.sortedByDescending { it.readTime }
                }
                if (isAdded) detailAdapter.setItems(shows)
            }
        }
    }

}

/** 日历格数据 */
private data class CalendarDayCell(
    val date: LocalDate?,
    val dayNum: String,
    val readTime: Long,
    val bookCount: Int,
    val selected: Boolean,
    val isToday: Boolean
) {
    companion object {
        fun empty() = CalendarDayCell(null, "", 0, 0, false, false)
    }
}

private class CalendarDayGridAdapter(
    context: Context,
    private val onSelect: (CalendarDayCell) -> Unit
) : RecyclerAdapter<CalendarDayCell, ItemReadDataCalendarDayBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemReadDataCalendarDayBinding {
        return ItemReadDataCalendarDayBinding.inflate(inflater, parent, false)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemReadDataCalendarDayBinding) {
        binding.tvDay.setOnClickListener {
            getItem(holder.layoutPosition)?.let(onSelect)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemReadDataCalendarDayBinding,
        item: CalendarDayCell,
        payloads: MutableList<Any>
    ) {
        binding.tvDay.text = item.dayNum
        if (item.date == null) {
            binding.tvDay.text = ""
            binding.tvDay.background = null
            return
        }
        binding.tvDay.text = item.dayNum
        val color = if (item.readTime > 0) {
            val primary = context.primaryColor
            val alpha = when {
                item.readTime >= 3 * 60 * 60 * 1000L -> 220
                item.readTime >= 60 * 60 * 1000L -> 170
                item.readTime >= 15 * 60 * 1000L -> 120
                else -> 70
            }
            ColorUtils.setAlphaComponent(primary, alpha)
        } else {
            ColorUtils.setAlphaComponent(0x888888, 28)
        }
        binding.tvDay.setBackgroundColor(color)
        binding.tvDay.setTextColor(
            if (item.readTime >= 60 * 60 * 1000L) {
                0xFF000000.toInt()
            } else {
                context.primaryTextColor
            }
        )
        if (item.selected) {
            binding.tvDay.setBackgroundResource(R.drawable.shape_calendar_day_selected)
        } else if (item.isToday) {
            binding.tvDay.setBackgroundResource(R.drawable.shape_calendar_day_today)
        } else {
            binding.tvDay.background = null
        }
    }
}
