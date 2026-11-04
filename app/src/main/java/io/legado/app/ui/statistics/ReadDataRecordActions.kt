package io.legado.app.ui.statistics

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared confirmation and existing serialized deletion for both record and ranking UI. */
internal fun confirmDeleteReadData(context: Context, bookName: String, onChanged: (() -> Unit)? = null) {
    (context as? LifecycleOwner)?.lifecycleScope?.launch {
        context.alert(R.string.delete) {
            // 明确告知删除范围：书名聚合行，含同名不同作者书籍的统计；不动书籍/进度/书源
            setMessage(context.getString(R.string.read_data_del_record_confirm, bookName))
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
                                    appDb.readRecordDao.deleteByName(bookName)
                                    appDb.readRecordDetailDao.deleteByName(bookName)
                                }
                                if (ReadBook.book?.name == bookName) {
                                    ReadBook.resetReadRecordCache()
                                }
                                if (ReadManga.book?.name == bookName) {
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
