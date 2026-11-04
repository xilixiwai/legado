package io.legado.app.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReadDataNavigationTest {
    private val rows = listOf(
        ReadDataBookShow("Beta", "Author A", 60_000, 300, 0),
        ReadDataBookShow("Alpha", "Author B", 90_000, 100, 0),
        ReadDataBookShow("Beta", "Author C", 30_000, 200, 2)
    )

    @Test
    fun `duration ranking preserves identities and precise durations`() {
        val ranked = rows.filterAndSortReadData("", 1)
        assertEquals(listOf(90_000L, 60_000L, 30_000L), ranked.map { it.readTime })
        assertEquals(rows.toSet(), ranked.toSet())
        assertEquals(180_000L, ranked.sumOf { it.readTime })
    }

    @Test
    fun `search filters display without merging same-name authors`() {
        val matching = rows.filterAndSortReadData("bETA", 1)
        assertEquals(listOf("Author A", "Author C"), matching.map { it.bookAuthor })
        assertEquals(90_000L, matching.sumOf { it.readTime })
        assertEquals(3, rows.size)
    }

    @Test
    fun `recent reading sort is descending and keeps time intact`() {
        assertEquals(listOf(60_000L, 30_000L, 90_000L),
            rows.filterAndSortReadData("", 2).map { it.readTime })
    }

    @Test
    fun `name sorting remains available after record tab removal`() {
        assertEquals(listOf("Alpha", "Beta", "Beta"),
            rows.filterAndSortReadData("", 0).map { it.bookName })
    }

    @Test
    fun `search with no results yields an empty list`() {
        assertTrue(rows.filterAndSortReadData("missing", 1).isEmpty())
    }

    @Test
    fun `preferences contain exactly one unified entry`() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(file("app/src/main/res/xml/pref_main.xml"))
        val nodes = document.getElementsByTagName("io.legado.app.lib.prefs.Preference")
        val android = "http://schemas.android.com/apk/res/android"
        val preferences = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        val keys = preferences.map { it.getAttributeNS(android, "key") }
        assertEquals(1, keys.count { it == "readDataCenter" })
        assertFalse(keys.contains("readRecord"))
        assertFalse(keys.contains("readingStatistics"))
        assertEquals("@string/read_data_center", preferences.single {
            it.getAttributeNS(android, "key") == "readDataCenter"
        }.getAttributeNS(android, "title"))
    }

    @Test
    fun `only overview calendar and ranking are navigable tabs`() {
        val source = file("app/src/main/java/io/legado/app/ui/statistics/ReadDataCenterActivity.kt").readText()
        assertTrue(source.contains("getCount(): Int = 3"))
        assertTrue(source.contains("ReadDataOverviewFragment()"))
        assertTrue(source.contains("ReadDataCalendarFragment()"))
        assertTrue(source.contains("ReadDataRankFragment()"))
        assertFalse(source.contains("ReadDataRecordFragment"))
    }

    private fun file(path: String): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path)
    }
}
