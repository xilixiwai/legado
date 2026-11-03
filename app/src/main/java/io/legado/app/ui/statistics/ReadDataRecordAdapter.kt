package io.legado.app.ui.statistics

import android.content.Context
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.databinding.ItemReadRecordBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toReadDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 阅读数据中心 - 记录 Tab 列表（原阅读记录页同款行为：累计时长 + 最后阅读日期，
 * 点击跳书/转搜索单书删除带确认）。排序与搜索由外部（Activity/Fragment）完成后 setItems。
 */
class ReadDataRecordAdapter(context: Context) :
    RecyclerAdapter<ReadRecordShow, ItemReadRecordBinding>(context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun getViewBinding(parent: ViewGroup): ItemReadRecordBinding {
        return ItemReadRecordBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemReadRecordBinding,
        item: ReadRecordShow,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            tvBookName.text = item.bookName
            tvReadingTime.text = item.readTime.toReadDuration()
            tvLastReadTime.text = if (item.lastRead > 0) {
                dateFormat.format(item.lastRead)
            } else {
                ""
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemReadRecordBinding) {
        binding.apply {
            root.setOnClickListener {
                val item = getItem(holder.layoutPosition) ?: return@setOnClickListener
                (context as? LifecycleOwner)?.lifecycleScope?.launch {
                    val book = withContext(Dispatchers.IO) {
                        appDb.bookDao.findByName(item.bookName).firstOrNull()
                    }
                    if (book == null) {
                        SearchActivity.start(context, item.bookName)
                    } else {
                        context.startActivityForBook(book)
                    }
                }
            }
            tvRemove.setOnClickListener {
                getItem(holder.layoutPosition)?.let { item ->
                    sureDelAlert(item)
                }
            }
        }
    }

    private fun sureDelAlert(item: ReadRecordShow) {
        (context as? LifecycleOwner)?.lifecycleScope?.launch {
            context.alert(R.string.delete) {
                // 明确告知删除范围：书名聚合行，含同名不同作者书籍的统计；不动书籍/进度/书源
                setMessage(context.getString(R.string.read_data_del_record_confirm, item.bookName))
                yesButton {
                    (context as? LifecycleOwner)?.lifecycleScope?.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                // 与计时结算共用同一单线程执行器：排队中的旧结算先落库再被删除，
                                // 避免删除与结算交错；删除后重置在读书籍的内存累计，
                                // 防止下一次结算把删除前的旧值整行写回（数据复活）
                                ReadBook.executor.submit {
                                    appDb.runInTransaction {
                                        // 原累计表按书名存储(历史粒度限制,与旧版行为一致)；
                                        // 每日明细同步按书名清除，保持各 Tab 口径一致
                                        appDb.readRecordDao.deleteByName(item.bookName)
                                        appDb.readRecordDetailDao.deleteByName(item.bookName)
                                    }
                                    if (ReadBook.book?.name == item.bookName) {
                                        ReadBook.resetReadRecordCache()
                                    }
                                    if (ReadManga.book?.name == item.bookName) {
                                        ReadManga.resetReadRecordCache()
                                    }
                                }.get()
                            }.onFailure {
                                AppLog.put("删除阅读记录失败:${it.localizedMessage}", it)
                            }
                        }
                        onChanged?.invoke()
                    }
                }
                noButton()
            }
        }
    }

    /** 删除后由持有方刷新 */
    var onChanged: (() -> Unit)? = null

}
