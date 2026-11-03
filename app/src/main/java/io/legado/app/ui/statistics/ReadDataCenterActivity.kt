package io.legado.app.ui.statistics

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityReadDataCenterBinding
import io.legado.app.help.book.ReadRecordDetailHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 阅读数据中心：总览 / 日历 / 排行 / 记录 四 Tab。
 * 清空入口统一在右上菜单「清空全部阅读统计」（二次确认 + 明确范围提示），
 * 与计时写入共用同一执行器，保证清空与在途结算串行、清空后旧窗口不复活。
 */
class ReadDataCenterActivity : BaseActivity<ActivityReadDataCenterBinding>() {

    override val binding by viewBinding(ActivityReadDataCenterBinding::inflate)

    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.viewPager.adapter = ViewPagerAdapter(supportFragmentManager)
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        binding.viewPager.addOnPageChangeListener(object :
            androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                upSearchViewVisible(position == 3)
            }
        })
        initSearchView()
        upSearchViewVisible(false)
    }

    private fun upSearchViewVisible(visible: Boolean) {
        searchView.isVisibleCompat(visible)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                recordFragment()?.setSearchKey(newText)
                return false
            }
        })
    }

    private fun recordFragment(): ReadDataRecordFragment? {
        return supportFragmentManager.findFragmentByTag(makeFragmentName(3)) as? ReadDataRecordFragment
    }

    private fun makeFragmentName(position: Int): String {
        return "android:switcher:${binding.viewPager.id}:$position"
    }

    private fun refreshAll() {
        supportFragmentManager.fragments.forEach { fragment ->
            (fragment as? ReadDataOverviewFragment)?.reload()
            (fragment as? ReadDataCalendarFragment)?.reload()
            (fragment as? ReadDataRankFragment)?.reload()
            (fragment as? ReadDataRecordFragment)?.reload()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.read_data_center, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        recordFragment()?.let { record ->
            when (record.sortModeMenuChecked()) {
                1 -> menu.findItem(R.id.menu_sort_read_long)?.isChecked = true
                2 -> menu.findItem(R.id.menu_sort_read_time)?.isChecked = true
                else -> menu.findItem(R.id.menu_sort_name)?.isChecked = true
            }
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_name -> {
                recordFragment()?.onSortSelected(0)
                item.isChecked = true
            }

            R.id.menu_sort_read_long -> {
                recordFragment()?.onSortSelected(1)
                item.isChecked = true
            }

            R.id.menu_sort_read_time -> {
                recordFragment()?.onSortSelected(2)
                item.isChecked = true
            }

            R.id.menu_clear_all_statistics -> sureClearAlert()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun sureClearAlert() {
        alert(R.string.read_data_clear_all, R.string.read_data_clear_all_confirm) {
            yesButton {
                ReadRecordDetailHelper.clearAllStatistics { ok ->
                    runOnUiThread {
                        if (ok) {
                            toastOnUi(R.string.read_data_clear_done)
                        } else {
                            toastOnUi(R.string.read_data_clear_failed)
                        }
                        refreshAll()
                    }
                }
            }
            noButton()
        }
    }

    private inner class ViewPagerAdapter(fm: androidx.fragment.app.FragmentManager) :
        FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(position: Int): Fragment = when (position) {
            1 -> ReadDataCalendarFragment()
            2 -> ReadDataRankFragment()
            3 -> ReadDataRecordFragment()
            else -> ReadDataOverviewFragment()
        }

        override fun getCount(): Int = 4

        override fun getPageTitle(position: Int): CharSequence = when (position) {
            1 -> getString(R.string.read_data_tab_calendar)
            2 -> getString(R.string.read_data_tab_rank)
            3 -> getString(R.string.read_data_tab_record)
            else -> getString(R.string.read_data_tab_overview)
        }
    }

    companion object {
        private const val BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT =
            FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
    }

}

private fun SearchView.isVisibleCompat(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
