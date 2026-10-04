package io.legado.app.ui.statistics

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityReadingStatisticsBinding
import io.legado.app.databinding.ItemStatisticsBookBinding
import io.legado.app.help.book.BookReadStat
import io.legado.app.help.book.ReadingStatistics
import io.legado.app.help.book.buildReadingStatistics
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toReadDuration
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 阅读统计(只读页面)。
 *
 * 数据来源:现有 [io.legado.app.data.entities.Book] 阅读进度字段 + `readRecord` 表,
 * 仅做“查询/聚合/展示”,**不写入任何数据,也不参与阅读链路**。
 * 聚合逻辑集中在纯函数 [buildReadingStatistics],本类只负责加载与绑定。
 */
class ReadingStatisticsActivity : BaseActivity<ActivityReadingStatisticsBinding>() {

    override val binding by viewBinding(ActivityReadingStatisticsBinding::inflate)

    private val rankAdapter by lazy { StatBookAdapter(this) }
    private val recentAdapter by lazy { StatBookAdapter(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rvRank.adapter = rankAdapter
        binding.rvRecent.adapter = recentAdapter
        binding.nestedScroll.applyNavigationBarPadding()
        loadStatistics()
    }

    private fun loadStatistics() {
        lifecycleScope.launch {
            val statistics = withContext(IO) {
                buildReadingStatistics(
                    books = appDb.bookDao.all,
                    recordShows = appDb.readRecordDao.allShow,
                    recordRows = appDb.readRecordDao.all,
                    totalReadTime = appDb.readRecordDao.allTime
                )
            }
            bindOverview(statistics)
        }
    }

    private fun bindOverview(statistics: ReadingStatistics) = binding.run {
        val rank = statistics.rankByReadTime.take(ReadingStatistics.TOP_LIMIT)
        val recent = statistics.recentRead.take(ReadingStatistics.TOP_LIMIT)

        tvTotalTime.text = statistics.totalReadTime.toReadDuration()
        tvTotalBooks.text = statistics.totalBookCount.toString()
        tvReadingCount.text = statistics.readingCount.toString()
        tvFinishedCount.text = statistics.finishedCount.toString()
        tvReadChapters.text = statistics.estimatedReadChapters.toString()

        // 同一本书存在多条阅读记录时,聚合值可能偏高,如实提示(不隐藏、不修正数据)
        tvDuplicateWarning.isVisible = statistics.hasDuplicateRecords

        val noData = statistics.items.isEmpty()
        tvEmpty.isVisible = noData
        groupRank.isVisible = rank.isNotEmpty()
        groupRecent.isVisible = recent.isNotEmpty()

        rankAdapter.setItems(rank)
        recentAdapter.setItems(recent)
    }

    inner class StatBookAdapter(context: Context) :
        RecyclerAdapter<BookReadStat, ItemStatisticsBookBinding>(context) {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        override fun getViewBinding(parent: ViewGroup): ItemStatisticsBookBinding {
            return ItemStatisticsBookBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemStatisticsBookBinding,
            item: BookReadStat,
            payloads: MutableList<Any>,
        ) {
            binding.apply {
                ivCover.load(item.coverUrl, item.bookName, item.author, false, item.origin)
                tvBookName.text = item.bookName
                tvAuthor.text = item.author
                tvReadingTime.text = item.readTime.toReadDuration()
                tvProgress.text = item.progressPercent?.let { "$it%" } ?: ""
                tvLastReadTime.text = if (item.lastRead > 0) {
                    dateFormat.format(item.lastRead)
                } else {
                    ""
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemStatisticsBookBinding) {
            binding.root.setOnClickListener {
                val item = getItem(holder.layoutPosition) ?: return@setOnClickListener
                lifecycleScope.launch {
                    val book = withContext(IO) {
                        appDb.bookDao.findByName(item.bookName).firstOrNull()
                    }
                    if (book == null) {
                        SearchActivity.start(this@ReadingStatisticsActivity, item.bookName)
                    } else {
                        startActivityForBook(book)
                    }
                }
            }
        }

    }

}
