package io.legado.app.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 阅读统计页面 XML 静态边界检查。
 *
 * 目的:防止重新引入“固定宽度 + wrap_content + 多个横向 TextView”这类在长数据下
 * 会互相挤压/溢出的布局,并保证长数字/长书名有明确的换行或省略策略。
 *
 * 检查项:
 * 1. 统计页整体不出现固定 dp 宽度;
 * 2. 概览 5 个统计值全部 `0dp + weight` 占满剩余宽度,并带 maxLines/ellipsize;
 * 3. 清空入口存在;
 * 4. 列表项除封面外不出现固定 dp 宽度,取值全部宽度受限 + 省略。
 */
class ReadingStatisticsLayoutTest {

    private val activity = source("app/src/main/res/layout/activity_reading_statistics.xml")
    private val item = source("app/src/main/res/layout/item_statistics_book.xml")

    // 固定 dp 宽度(排除合法的 0dp + weight 写法)
    private val fixedWidth = Regex("android:layout_width=\"(?!0dp)[0-9]+(\\.[0-9]+)?dp\"")

    // 1. 统计页不得出现固定 dp 宽度(长数据下会互相争抢宽度)
    @Test
    fun `statistics page has no fixed dp widths`() {
        val found = fixedWidth.findAll(activity).map { it.value }.toList()
        assertTrue("统计页不应出现固定 dp 宽度: $found", found.isEmpty())
    }

    // 2. 概览 5 个统计值必须 0dp + weight,并带 maxLines/ellipsize
    @Test
    fun `overview values are width bounded and ellipsized`() {
        listOf(
            "tv_total_time",
            "tv_total_books",
            "tv_reading_count",
            "tv_finished_count",
            "tv_read_chapters"
        ).forEach { id ->
            val block = element(activity, id)
            assertTrue("$id 必须使用 0dp 宽度", block.contains("android:layout_width=\"0dp\""))
            assertTrue(
                "$id 必须使用 layout_weight 占满剩余宽度",
                block.contains("android:layout_weight=\"1\"")
            )
            assertTrue("$id 必须限制行数", block.contains("android:maxLines="))
            assertTrue("$id 必须配置省略策略", block.contains("android:ellipsize="))
        }
    }

    // 3. 清空入口必须存在
    @Test
    fun `clear entry exists`() {
        val block = element(activity, "tv_clear_record")
        assertTrue(block.contains("@string/statistics_clear_record"))
    }

    // 4a. 列表项只允许封面使用固定宽度
    @Test
    fun `item page only fixes cover width`() {
        val found = fixedWidth.findAll(item).map { it.value }.toList()
        assertEquals("列表项只允许封面使用固定宽度: $found", 1, found.size)
        assertTrue(element(item, "iv_cover").contains("android:layout_width=\"44dp\""))
    }

    // 4b. 列表项取值全部宽度受限 + 省略
    @Test
    fun `item values are width bounded and ellipsized`() {
        listOf(
            "tv_book_name",
            "tv_author",
            "tv_reading_time",
            "tv_progress",
            "tv_last_read_time"
        ).forEach { id ->
            val block = element(item, id)
            assertTrue("$id 必须使用 0dp 宽度", block.contains("android:layout_width=\"0dp\""))
            assertTrue("$id 必须限制行数", block.contains("android:maxLines="))
            assertTrue("$id 必须配置省略策略", block.contains("android:ellipsize="))
        }
    }

    /** 从 android:id 所在元素的开标签中抽取属性文本 */
    private fun element(xml: String, id: String): String {
        val marker = "android:id=\"@+id/$id\""
        val at = xml.indexOf(marker)
        assertTrue("缺少元素 $id", at >= 0)
        val open = xml.lastIndexOf('<', at)
        val close = xml.indexOf('>', at)
        return xml.substring(open, close + 1)
    }

    private fun source(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path).readText().replace("\r\n", "\n")
    }
}
