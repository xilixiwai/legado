package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemDownloadBinding
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class CacheAdapter(context: Context, private val callBack: CallBack) :
    DiffRecyclerAdapter<Book, ItemDownloadBinding>(context) {

    /**
     * 列表 diff 应用完成后的回调,用于排序切换后回到顶部。
     */
    var onListChanged: (() -> Unit)? = null

    override fun onCurrentListChanged() {
        super.onCurrentListChanged()
        onListChanged?.invoke()
    }

    override val diffItemCallback: DiffUtil.ItemCallback<Book>
        get() = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                        && oldItem.author == newItem.author
            }

        }

    override fun getViewBinding(parent: ViewGroup): ItemDownloadBinding {
        return ItemDownloadBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemDownloadBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.run {
            // 记录当前绑定的书籍,避免异步 diff/排序后按位置取到失效或错误的书籍
            tvName.tag = item
            if (payloads.isEmpty()) {
                tvName.text = item.name
                tvAuthor.text = context.getString(R.string.author_show, item.getRealAuthor())
                if (item.isLocal) {
                    tvDownload.setText(R.string.local_book)
                } else {
                    val cs = callBack.cacheChapters[item.bookUrl]
                    if (cs == null) {
                        tvDownload.setText(R.string.loading)
                    } else {
                        tvDownload.text =
                            context.getString(
                                R.string.download_count,
                                cs.size,
                                item.totalChapterNum
                            )
                    }
                }
            } else {
                if (item.isLocal) {
                    tvDownload.setText(R.string.local_book)
                } else {
                    val cacheSize = callBack.cacheChapters[item.bookUrl]?.size ?: 0
                    tvDownload.text =
                        context.getString(R.string.download_count, cacheSize, item.totalChapterNum)
                }
            }
            upDownloadIv(ivDownload, item)
            upExportInfo(tvMsg, progressExport, item)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemDownloadBinding) {
        binding.run {
            // 先清空绑定数据,convert 时再写入当前书籍,避免复用瞬间点到上一本书
            tvName.tag = null
            // 只有书名区域响应点击,复用书架的阅读页跳转方式
            tvName.setOnClickListener {
                val book = (tvName.tag as? Book) ?: let {
                    // 理论上 tag 在 convert 时已写入,这里仅作兜底
                    val position = holder.bindingAdapterPosition
                    if (position >= 0) getItem(position) else null
                }
                book?.let { callBack.openBook(it) }
            }
            ivDownload.setOnClickListener {
                getItem(holder.layoutPosition)?.let { book ->
                    CacheBook.cacheBookMap[book.bookUrl]?.let {
                        if (!it.isStop()) {
                            CacheBook.remove(context, book.bookUrl)
                        } else {
                            CacheBook.start(context, book, 0, book.lastChapterIndex)
                        }
                    } ?: let {
                        CacheBook.start(context, book, 0, book.lastChapterIndex)
                    }
                }
            }
            tvExport.setOnClickListener {
                callBack.export(holder.layoutPosition)
            }
        }
    }

    private fun upDownloadIv(iv: ImageView, book: Book) {
        if (book.isLocal) {
            iv.gone()
        } else {
            iv.visible()
            CacheBook.cacheBookMap[book.bookUrl]?.let {
                if (!it.isStop()) {
                    iv.setImageResource(R.drawable.ic_stop_black_24dp)
                } else {
                    iv.setImageResource(R.drawable.ic_play_24dp)
                }
            } ?: let {
                iv.setImageResource(R.drawable.ic_play_24dp)
            }
        }
    }

    private fun upExportInfo(msgView: TextView, progressView: ProgressBar, book: Book) {
        val msg = callBack.exportMsg(book.bookUrl)
        if (msg != null) {
            msgView.text = msg
            msgView.visible()
            progressView.gone()
            return
        }
        msgView.gone()
        val progress = callBack.exportProgress(book.bookUrl)
        if (progress != null) {
            progressView.max = book.totalChapterNum
            progressView.progress = progress
            progressView.visible()
            return
        }
        progressView.gone()
    }

    interface CallBack {
        val cacheChapters: HashMap<String, HashSet<String>>
        fun openBook(book: Book)
        fun export(position: Int)
        fun exportProgress(bookUrl: String): Int?
        fun exportMsg(bookUrl: String): String?
    }
}