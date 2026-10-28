package io.legado.app.ui.book.changesource

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemChangeSourceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.SourceDiag
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.init.appCtx
import splitties.views.onLongClick


class ChangeBookSourceAdapter(
    context: Context,
    val viewModel: ChangeBookSourceViewModel,
    val callBack: CallBack
) : DiffRecyclerAdapter<SearchBook, ItemChangeSourceBinding>(context) {

    override val diffItemCallback = object : DiffUtil.ItemCallback<SearchBook>() {
        override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.bookUrl == newItem.bookUrl
        }

        override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.originName == newItem.originName
                    && oldItem.getDisplayLastChapterTitle() == newItem.getDisplayLastChapterTitle()
                    && oldItem.chapterWordCountText == newItem.chapterWordCountText
                    && oldItem.respondTime == newItem.respondTime
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemChangeSourceBinding {
        return ItemChangeSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChangeSourceBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            if (payloads.isEmpty()) {
                tvOrigin.text = item.originName
                tvAuthor.text = item.author
                tvLast.text = item.getDisplayLastChapterTitle()
                tvCurrentChapterWordCount.text = item.chapterWordCountText
                tvRespondTime.text = context.getString(R.string.respondTime, item.respondTime)
                if (callBack.oldBookUrl == item.bookUrl) {
                    ivChecked.visible()
                } else {
                    ivChecked.invisible()
                }
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvOrigin.text = item.originName
                            "latest" -> tvLast.text = item.getDisplayLastChapterTitle()
                            "upCurSource" -> if (callBack.oldBookUrl == item.bookUrl) {
                                ivChecked.visible()
                            } else {
                                ivChecked.invisible()
                            }
                        }
                    }
                }
            }
            val score = callBack.getBookScore(item)
            if (score > 0) {
                binding.ivBad.gone()
                binding.ivGood.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            } else if (score < 0) {
                binding.ivGood.gone()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
            } else {
                binding.ivGood.visible()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            }

            if (AppConfig.changeSourceLoadWordCount && !item.chapterWordCountText.isNullOrBlank()) {
                tvCurrentChapterWordCount.visible()
            } else {
                tvCurrentChapterWordCount.gone()
            }

            if (AppConfig.changeSourceLoadWordCount && item.respondTime >= 0) {
                tvRespondTime.visible()
            } else {
                tvRespondTime.gone()
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChangeSourceBinding) {
        binding.ivGood.setOnClickListener {
            if (binding.ivBad.isVisible) {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                binding.ivBad.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                binding.ivBad.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        binding.ivBad.setOnClickListener {
            if (binding.ivGood.isVisible) {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
                binding.ivGood.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, -1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
                binding.ivGood.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                // [诊断] 只记录点击判定结果
                SourceDiag.click(it.bookUrl, callBack.oldBookUrl, it.origin, callBack.oldBookOrigin)
                if (!isDuplicateSelection(it.bookUrl, it.origin, callBack.oldBookUrl, callBack.oldBookOrigin)) {
                    callBack.changeTo(it)
                }
            }
        }
        holder.itemView.onLongClick {
            showMenu(holder.itemView, getItem(holder.layoutPosition))
        }
    }

    private fun showMenu(view: View, searchBook: SearchBook?) {
        searchBook ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.change_source_item)
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_top_source -> {
                    callBack.topSource(searchBook)
                }

                R.id.menu_bottom_source -> {
                    callBack.bottomSource(searchBook)
                }

                R.id.menu_edit_source -> {
                    callBack.editSource(searchBook)
                }

                R.id.menu_disable_source -> {
                    callBack.disableSource(searchBook)
                }

                R.id.menu_delete_source -> context.alert(R.string.draw) {
                    setMessage(context.getString(R.string.sure_del) + "\n" + searchBook.originName)
                    noButton()
                    yesButton {
                        callBack.deleteSource(searchBook)
                        updateItems(0, itemCount, listOf<Int>())
                    }
                }
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        val oldBookUrl: String?

        /** 当前书籍的书源归属，用于区分「地址相同但书源不同」 */
        val oldBookOrigin: String?
        fun changeTo(searchBook: SearchBook)
        fun topSource(searchBook: SearchBook)
        fun bottomSource(searchBook: SearchBook)
        fun editSource(searchBook: SearchBook)
        fun disableSource(searchBook: SearchBook)
        fun deleteSource(searchBook: SearchBook)
        fun setBookScore(searchBook: SearchBook, score: Int)
        fun getBookScore(searchBook: SearchBook): Int
    }
}

/**
 * 换源列表点击的「重复选择」判定。
 *
 * 只有候选书籍的地址**和**书源归属都与当前书籍相同，才算重复选择并跳过；
 * 任一项不同都允许进入原有换源流程。
 *
 * 为什么不能只看 URL：`books.bookUrl` 与 `books.origin` 是两个独立字段，
 * 「批量替换域名」只改 `bookUrl` / `tocUrl`，明确不动 `origin`。换域名后旧书的
 * `bookUrl` 已经是新域名，新书源返回的候选地址与它完全相同，此时只看 URL 会把
 * 「同一本书、不同书源」误判为重复点击，点击后毫无反应。
 *
 * 独立成顶层函数是为了能在 JVM 单元测试里直接验证判定结果，不依赖 RecyclerView。
 */
internal fun isDuplicateSelection(
    candidateBookUrl: String?,
    candidateOrigin: String?,
    oldBookUrl: String?,
    oldBookOrigin: String?
): Boolean = candidateBookUrl == oldBookUrl && candidateOrigin == oldBookOrigin
