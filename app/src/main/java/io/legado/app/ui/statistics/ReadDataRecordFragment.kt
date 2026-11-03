package io.legado.app.ui.statistics

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.getInt
import io.legado.app.utils.putInt
import io.legado.app.utils.toReadDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.utils.cnCompare

/**
 * 阅读数据中心 - 记录。
 * 完整保留原阅读记录页能力：搜索、排序（书名/时长/最近阅读）、跳转、单条删除；
 * 清空入口统一收口到 Activity 菜单「清空全部阅读统计」。
 */
class ReadDataRecordFragment : BaseFragment(R.layout.fragment_read_data_record) {

    private val adapter by lazy { ReadDataRecordAdapter(requireContext()) }

    private var sortMode
        get() = LocalConfig.getInt("readRecordSort")
        set(value) {
            LocalConfig.putInt("readRecordSort", value)
        }

    private var searchKey: String? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.recycler_view).adapter = adapter
        adapter.onChanged = { initData() }
        initData()
    }

    override fun onResume() {
        super.onResume()
        initData()
    }

    fun reload() {
        if (isAdded) {
            initData()
        }
    }

    fun setSearchKey(key: String?) {
        searchKey = key
        if (isAdded) {
            initData()
        }
    }

    fun sortModeMenuChecked(): Int = sortMode

    fun onSortSelected(mode: Int) {
        sortMode = mode
        initData()
    }

    private fun initData() {
        lifecycleScope.launch {
            val readRecords = withContext(Dispatchers.IO) {
                appDb.readRecordDao.search(searchKey ?: "").let { records ->
                    when (sortMode) {
                        1 -> records.sortedByDescending { it.readTime }
                        2 -> records.sortedByDescending { it.lastRead }
                        else -> records.sortedWith { o1, o2 ->
                            o1.bookName.cnCompare(o2.bookName)
                        }
                    }
                }
            }
            val allTime = withContext(Dispatchers.IO) {
                appDb.readRecordDao.allTime
            }
            if (!isAdded) return@launch
            adapter.setItems(readRecords)
            view?.findViewById<TextView>(R.id.tv_reading_time)?.text = allTime.toReadDuration()
            view?.findViewById<TextView>(R.id.tv_book_name)?.text =
                getString(R.string.all_read_time)
        }
    }

}
