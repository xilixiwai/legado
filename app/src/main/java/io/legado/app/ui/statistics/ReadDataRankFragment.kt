package io.legado.app.ui.statistics

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.help.book.ReadRecordDetailHelper
import io.legado.app.lib.theme.primaryTextColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 阅读数据中心 - 排行。
 * 累计口径来自 readRecord（历史数据，含多设备聚合）；本周/本月来自每日明细。
 * 类型筛选：明细行用 bookType 快照；累计行按书名查 books 类型，查不到按小说处理。
 */
class ReadDataRankFragment : BaseFragment(R.layout.fragment_read_data_rank) {

    private var period = 0 // 0=累计 1=本周 2=本月
    private var type = 0 // 0=全部 1=小说 2=漫画
    private val rankAdapter by lazy { ReadDataRankAdapter(requireContext()) }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.recycler_view).run {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = rankAdapter
        }
        bindChips(view)
    }

    override fun onResume() {
        super.onResume()
        loadRank()
    }

    fun reload() {
        if (isAdded) {
            loadRank()
        }
    }

    private fun bindChips(view: View) {
        val periodChips = listOf(
            view.findViewById<TextView>(R.id.tv_period_all_time),
            view.findViewById<TextView>(R.id.tv_period_week),
            view.findViewById<TextView>(R.id.tv_period_month)
        )
        val typeChips = listOf(
            view.findViewById<TextView>(R.id.tv_type_all),
            view.findViewById<TextView>(R.id.tv_type_novel),
            view.findViewById<TextView>(R.id.tv_type_manga)
        )
        fun refreshChips() {
            periodChips.forEachIndexed { index, chip ->
                chip.setTextColor(
                    if (index == period) requireContext().primaryTextColor else chip.currentTextColor
                )
                chip.paint.isFakeBoldText = index == period
            }
            typeChips.forEachIndexed { index, chip ->
                chip.setTextColor(
                    if (index == type) requireContext().primaryTextColor else chip.currentTextColor
                )
                chip.paint.isFakeBoldText = index == type
            }
        }
        periodChips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                period = index
                refreshChips()
                loadRank()
            }
        }
        typeChips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                type = index
                refreshChips()
                loadRank()
            }
        }
        refreshChips()
    }

    private fun loadRank() {
        lifecycleScope.launch {
            val shows = withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                when (period) {
                    1 -> {
                        val start = ReadRecordDetailHelper.weekStartOf(today)
                        val end = start.plusWeeks(1)
                        appDb.readRecordDetailDao.rankRange(start.toString(), end.toString())
                            .filterType().map {
                                ReadDataBookShow.fromRank(
                                    it.bookName, it.bookAuthor, it.readTime,
                                    it.lastReadTime, it.bookType
                                )
                            }
                    }

                    2 -> {
                        val start = today.withDayOfMonth(1)
                        val end = start.plusMonths(1)
                        appDb.readRecordDetailDao.rankRange(start.toString(), end.toString())
                            .filterType().map {
                                ReadDataBookShow.fromRank(
                                    it.bookName, it.bookAuthor, it.readTime,
                                    it.lastReadTime, it.bookType
                                )
                            }
                    }

                    else -> {
                        // 累计口径：readRecord 按书名聚合（含多设备），类型从 books 补齐
                        appDb.readRecordDao.allShow.mapNotNull { show ->
                            val book = appDb.bookDao.findByName(show.bookName).firstOrNull()
                            val bookType = book?.type ?: io.legado.app.constant.BookType.text
                            val isManga = bookType and io.legado.app.constant.BookType.image != 0
                            val match = when (type) {
                                1 -> !isManga
                                2 -> isManga
                                else -> true
                            }
                            if (!match) {
                                null
                            } else {
                                ReadDataBookShow(
                                    bookName = show.bookName,
                                    bookAuthor = book?.author ?: "",
                                    readTime = show.readTime,
                                    lastRead = show.lastRead,
                                    bookType = bookType,
                                    coverUrl = book?.getDisplayCover(),
                                    origin = book?.origin,
                                    inBookshelf = book != null
                                )
                            }
                        }.sortedByDescending { it.readTime }
                    }
                }
            }
            val rootView = view ?: return@launch
            rankAdapter.setItems(shows)
            rootView.findViewById<TextView>(R.id.tv_empty).isVisible = shows.isEmpty()
        }
    }

    private fun List<io.legado.app.data.entities.DetailBookRankShow>.filterType():
            List<io.legado.app.data.entities.DetailBookRankShow> {
        if (type == 0) return this
        return filter {
            val isManga = it.isManga
            if (type == 1) !isManga else isManga
        }
    }

}
