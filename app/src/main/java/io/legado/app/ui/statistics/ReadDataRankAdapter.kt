package io.legado.app.ui.statistics

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.databinding.ItemStatisticsBookBinding
import io.legado.app.R
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toReadDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 阅读数据中心「最近阅读 / 排行」列表。
 * 点击跳转书籍；书已删除时与原阅读记录页一致，转搜索。
 */
class ReadDataRankAdapter(context: Context, private val openDetails: Boolean = true) :
    RecyclerAdapter<ReadDataBookShow, ItemStatisticsBookBinding>(context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    var onDelete: ((ReadDataBookShow) -> Unit)? = null

    override fun getViewBinding(parent: ViewGroup): ItemStatisticsBookBinding {
        return ItemStatisticsBookBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemStatisticsBookBinding,
        item: ReadDataBookShow,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            ivCover.load(item.coverUrl, item.bookName, item.bookAuthor, false, item.origin)
            tvBookName.text = item.bookName
            tvAuthor.text = item.bookAuthor
            tvReadingTime.text = item.readTime.toReadDuration()
            tvProgressTag.isVisible = false
            tvProgress.isVisible = false
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
            (context as? LifecycleOwner)?.lifecycleScope?.launch {
                val book = withContext(Dispatchers.IO) {
                    appDb.bookDao.getByNameAuthor(item.bookName, item.bookAuthor)
                        ?: appDb.bookDao.findByName(item.bookName).firstOrNull()
                }
                if (book == null) {
                    io.legado.app.ui.book.search.SearchActivity.start(context, item.bookName)
                } else if (openDetails) {
                    context.startActivity<BookInfoActivity> {
                        putExtra("bookUrl", book.bookUrl)
                    }
                } else {
                    context.startActivityForBook(book)
                }
            }
        }
        binding.root.setOnLongClickListener {
            val item = getItem(holder.layoutPosition) ?: return@setOnLongClickListener false
            val delete = onDelete ?: return@setOnLongClickListener false
            context.selector(item.bookName, listOf(context.getString(R.string.delete))) { _, _ ->
                delete(item)
            }
            true
        }
    }

}
