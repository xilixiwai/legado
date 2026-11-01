package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.MD5Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 章节正文缓存键策略测试。
 *
 * 覆盖：新缓存键规则（唯一标题不含 index / 重复标题保留 index）、目录重排与 URL 变化
 * 后键稳定、旧格式兼容读取（真实文件读写）、删除正确文件、缓存标记归一、字数统计桥接、
 * 整本下载跳过语义、非默认 suffix。
 *
 * 注册表通过 [ChapterTitleRegistry.titlesLoader] 注入，不依赖 Room；
 * 文件场景使用 TemporaryFolder 真实读写，不做 mock。
 */
class ChapterCacheKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun reset() {
        ChapterTitleRegistry.invalidateAll()
        ChapterTitleRegistry.titlesLoader = null
    }

    @After
    fun cleanUp() {
        ChapterTitleRegistry.invalidateAll()
        ChapterTitleRegistry.titlesLoader = null
    }

    private fun md5(title: String) = MD5Utils.md5Encode16(title)

    private fun chapter(
        title: String,
        index: Int,
        url: String = "https://a.b/$index.html",
        bookUrl: String = "book1"
    ) = BookChapter(url = url, title = title, index = index, bookUrl = bookUrl)

    // ============================================================================================
    // 新缓存键规则
    // ============================================================================================

    @Test
    fun `唯一标题的新缓存键不含index`() {
        ChapterTitleRegistry.update("book1", listOf("第一章", "第二章"))
        val ch = chapter("第一章", index = 5)
        assertEquals("${md5("第一章")}.nb", ch.getFileName())
        assertFalse(ch.getFileName().contains("00005"))
    }

    @Test
    fun `重复标题的新缓存键保留index区分`() {
        ChapterTitleRegistry.update("book1", listOf("第1章", "第1章", "第2章"))
        val ch3 = chapter("第1章", index = 3)
        val ch7 = chapter("第1章", index = 7)
        assertEquals(String.format("%05d-%s.nb", 3, md5("第1章")), ch3.getFileName())
        assertEquals(String.format("%05d-%s.nb", 7, md5("第1章")), ch7.getFileName())
        assertNotEquals(ch3.getFileName(), ch7.getFileName())
        // 同一目录中唯一标题不受影响
        assertEquals("${md5("第2章")}.nb", chapter("第2章", index = 8).getFileName())
    }

    @Test
    fun `不同标题不会生成相同的缓存键`() {
        ChapterTitleRegistry.update("book1", listOf("标题甲", "标题乙"))
        val a = chapter("标题甲", index = 0)
        val b = chapter("标题乙", index = 0)
        assertNotEquals(a.getFileName(), b.getFileName())
        assertEquals(a.getFileName(), "${md5("标题甲")}.nb")
        assertEquals(b.getFileName(), "${md5("标题乙")}.nb")
    }

    @Test
    fun `注册表未知时保守按重复处理返回旧格式`() {
        // 未 update 也无 loader：无法判定重复性 → 退回带 index 的旧格式，等价旧行为，不串章
        val ch = chapter("任意标题", index = 5)
        assertEquals(ch.getLegacyFileName(), ch.getFileName())
    }

    @Test
    fun `loader失败时保守按重复处理且不缓存结果`() {
        ChapterTitleRegistry.titlesLoader = { null }
        val ch = chapter("任意标题", index = 5)
        assertEquals(ch.getLegacyFileName(), ch.getFileName())
        // 恢复 loader 后可正常判定（失败结果未被缓存）
        ChapterTitleRegistry.titlesLoader = { listOf("任意标题", "其他") }
        assertEquals("${md5("任意标题")}.nb", ch.getFileName())
    }

    @Test
    fun `loader懒加载有缓存不重复调用`() {
        var calls = 0
        ChapterTitleRegistry.titlesLoader = {
            calls++
            listOf("A", "B")
        }
        val ch = chapter("A", index = 1)
        // A、B 各只出现一次，均非重复标题
        assertFalse(ChapterTitleRegistry.isTitleDuplicate("book1", "A"))
        assertEquals("${md5("A")}.nb", ch.getFileName())
        assertEquals("${md5("B")}.nb", chapter("B", index = 2).getFileName())
        assertEquals(1, calls)
    }

    @Test
    fun `invalidate后重新懒加载`() {
        ChapterTitleRegistry.titlesLoader = { listOf("A") }
        assertEquals("${md5("A")}.nb", chapter("A", index = 0).getFileName())
        ChapterTitleRegistry.invalidate("book1")
        ChapterTitleRegistry.titlesLoader = { listOf("A", "A") }
        assertEquals(chapter("A", index = 0).getLegacyFileName(), chapter("A", index = 0).getFileName())
    }

    @Test
    fun `update空目录不覆盖已有判定`() {
        ChapterTitleRegistry.update("book1", listOf("A", "A"))
        ChapterTitleRegistry.update("book1", emptyList())
        assertEquals(String.format("%05d-%s.nb", 0, md5("A")), chapter("A", index = 0).getFileName())
    }

    // ============================================================================================
    // 缓存键稳定性（目录重排 / URL 变化 / 标题变化）
    // ============================================================================================

    @Test
    fun `章节index改变后唯一标题缓存键不变`() {
        ChapterTitleRegistry.update("book1", listOf("第一章", "第二章", "第三章"))
        val ch = chapter("第二章", index = 1)
        val before = ch.getFileName()
        // 模拟目录刷新重排：index 重排、注册表按（不变的）标题集刷新
        ch.index = 42
        ChapterTitleRegistry.update("book1", listOf("第三章", "第一章", "第二章"))
        assertEquals(before, ch.getFileName())
    }

    @Test
    fun `章节URL变化后缓存键不变`() {
        ChapterTitleRegistry.update("book1", listOf("第一章"))
        val ch = chapter("第一章", index = 0, url = "https://old.com/1.html")
        val before = ch.getFileName()
        ch.url = "https://new.com/chapter/1"
        assertEquals(before, ch.getFileName())
    }

    @Test
    fun `标题变化后不会命中原标题的缓存键`() {
        ChapterTitleRegistry.update("book1", listOf("旧标题"))
        val oldCh = chapter("旧标题", index = 0)
        val oldKey = oldCh.getFileName()
        // 目录刷新后标题变化：真实链路会解析出新的章节对象（titleMD5 按新标题懒加载）
        ChapterTitleRegistry.update("book1", listOf("新标题"))
        val newCh = chapter("新标题", index = 0)
        assertNotEquals(oldKey, newCh.getFileName())
        // 旧标题的缓存文件不会被新标题章节读取
        assertEquals("${md5("新标题")}.nb", newCh.getFileName())
    }

    @Test
    fun `重复标题章节index互换后仍可区分`() {
        ChapterTitleRegistry.update("book1", listOf("番外", "番外"))
        val a = chapter("番外", index = 2)
        val b = chapter("番外", index = 9)
        val keyA = a.getFileName()
        val keyB = b.getFileName()
        assertNotEquals(keyA, keyB)
        a.index = 9
        b.index = 2
        assertEquals(keyB, a.getFileName())
        assertEquals(keyA, b.getFileName())
    }

    @Test
    fun `不同书籍的重复判定互不影响`() {
        ChapterTitleRegistry.update("book1", listOf("A", "A"))
        ChapterTitleRegistry.update("book2", listOf("A"))
        assertEquals(String.format("%05d-%s.nb", 0, md5("A")), chapter("A", 0, bookUrl = "book1").getFileName())
        assertEquals("${md5("A")}.nb", chapter("A", 0, bookUrl = "book2").getFileName())
    }

    // ============================================================================================
    // 旧格式兼容与文件操作（真实文件读写）
    // ============================================================================================

    @Test
    fun `旧格式文件名格式与旧实现一致`() {
        val ch = chapter("标题", index = 12)
        assertEquals(String.format("%05d-%s.nb", 12, md5("标题")), ch.getLegacyFileName())
        assertEquals(String.format("%05d-%s.nr", 12, md5("标题")), ch.getLegacyFileName("nr"))
    }

    @Test
    fun `新格式存在时优先读取新格式`() {
        val dir = tmp.newFolder()
        val ch = chapter("唯一", index = 3)
        ch.bookUrl = "book1"
        ChapterTitleRegistry.update("book1", listOf("唯一"))
        File(dir, ch.getFileName()).writeText("new-content")
        File(dir, ch.getLegacyFileName()).writeText("legacy-content")
        val file = resolveChapterCacheFile(dir, ch.getFileName(), ch.getLegacyFileName())
        assertEquals("new-content", file?.readText())
    }

    @Test
    fun `新格式不存在时读取旧格式缓存`() {
        val dir = tmp.newFolder()
        val ch = chapter("唯一", index = 3)
        ch.bookUrl = "book1"
        ChapterTitleRegistry.update("book1", listOf("唯一"))
        File(dir, ch.getLegacyFileName()).writeText("legacy-content")
        val file = resolveChapterCacheFile(dir, ch.getFileName(), ch.getLegacyFileName())
        assertEquals("legacy-content", file?.readText())
    }

    @Test
    fun `新旧格式都不存在时返回null`() {
        val dir = tmp.newFolder()
        val ch = chapter("唯一", index = 3)
        assertNull(resolveChapterCacheFile(dir, ch.getFileName(), ch.getLegacyFileName()))
    }

    @Test
    fun `重复标题章节不会串章`() {
        val dir = tmp.newFolder()
        ChapterTitleRegistry.update("book1", listOf("第1章", "第1章"))
        val ch3 = chapter("第1章", index = 3)
        val ch7 = chapter("第1章", index = 7)
        File(dir, ch3.getFileName()).writeText("第三章的正文")
        File(dir, ch7.getFileName()).writeText("第七章的正文")
        assertEquals(
            "第三章的正文",
            resolveChapterCacheFile(dir, ch3.getFileName(), ch3.getLegacyFileName())?.readText()
        )
        assertEquals(
            "第七章的正文",
            resolveChapterCacheFile(dir, ch7.getFileName(), ch7.getLegacyFileName())?.readText()
        )
    }

    @Test
    fun `删除章节缓存删除新旧两个文件且不误删无关文件`() {
        val dir = tmp.newFolder()
        val ch = chapter("唯一", index = 3)
        ch.bookUrl = "book1"
        ChapterTitleRegistry.update("book1", listOf("唯一"))
        val newFile = File(dir, ch.getFileName()).apply { writeText("new") }
        val legacyFile = File(dir, ch.getLegacyFileName()).apply { writeText("legacy") }
        val other = File(dir, "99999-unrelated.nb").apply { writeText("other") }
        deleteChapterCacheFiles(dir, ch.getFileName(), ch.getLegacyFileName())
        assertFalse(newFile.exists())
        assertFalse(legacyFile.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `重复标题章节删除时同名文件只删一次`() {
        val dir = tmp.newFolder()
        val ch = chapter("重复", index = 1)
        ch.bookUrl = "book1"
        ChapterTitleRegistry.update("book1", listOf("重复", "重复"))
        // 重复标题：新键与旧键同名
        assertEquals(ch.getLegacyFileName(), ch.getFileName())
        val file = File(dir, ch.getFileName()).apply { writeText("c") }
        deleteChapterCacheFiles(dir, ch.getFileName(), ch.getLegacyFileName())
        assertFalse(file.exists())
    }

    @Test
    fun `非默认suffix的新旧文件名与解析行为一致`() {
        val dir = tmp.newFolder()
        val ch = chapter("唯一", index = 3)
        ch.bookUrl = "book1"
        ChapterTitleRegistry.update("book1", listOf("唯一"))
        assertEquals("${md5("唯一")}.nr", ch.getFileName("nr"))
        assertEquals(String.format("%05d-%s.nr", 3, md5("唯一")), ch.getLegacyFileName("nr"))
        // 新键命中
        File(dir, ch.getFileName("nr")).writeText("nr-new")
        assertEquals(
            "nr-new",
            resolveChapterCacheFile(dir, ch.getFileName("nr"), ch.getLegacyFileName("nr"))?.readText()
        )
        // 仅旧键命中
        val dir2 = tmp.newFolder()
        File(dir2, ch.getLegacyFileName("nr")).writeText("nr-legacy")
        assertEquals(
            "nr-legacy",
            resolveChapterCacheFile(dir2, ch.getFileName("nr"), ch.getLegacyFileName("nr"))?.readText()
        )
    }

    // ============================================================================================
    // 缓存标记 / 字数统计 / 整本下载跳过
    // ============================================================================================

    @Test
    fun `章节列表缓存标记新旧格式都命中且孤立旧文件不误标`() {
        ChapterTitleRegistry.update("book1", listOf("甲", "乙", "丙"))
        val chA = chapter("甲", index = 0) // 新格式已缓存
        val chB = chapter("乙", index = 1) // 仅旧格式已缓存（升级前下载，index 未变）
        val chC = chapter("丙", index = 2) // 未缓存
        val diskFiles = hashSetOf(
            chA.getFileName(),
            chB.getLegacyFileName(),
            "99999-orphan.nb"
        )
        val marked = matchChapterCacheNames(diskFiles, listOf(chA, chB, chC))
        assertTrue(marked.contains(chA.getFileName()))
        assertTrue(marked.contains(chB.getFileName()))
        assertFalse(marked.contains(chC.getFileName()))
        // 归一后只含当前缓存键，孤立旧文件不进入标记集
        assertFalse(marked.contains("99999-orphan.nb"))
        assertFalse(marked.contains(chB.getLegacyFileName()))
    }

    @Test
    fun `字数统计桥接不因目录重排而关联错误`() {
        ChapterTitleRegistry.update("book1", listOf("A", "B", "C"))
        // 旧目录（刷新前，DB 中的 index）
        val oldList = listOf(chapter("A", 0), chapter("B", 1), chapter("C", 2))
        // 新目录（刷新后重排 + index 重分配）
        val newList = listOf(chapter("C", 0), chapter("A", 1), chapter("B", 2))
        val wordCounts = mapOf("A" to "1千字", "B" to "2千字", "C" to "3千字")
        oldList.forEach { it.wordCount = wordCounts[it.title] }
        // BookChapterList.getWordCount 的桥接方式：按缓存键做 map
        val map = oldList.associateBy({ it.getFileName() }, { it.wordCount })
        newList.forEach { newCh ->
            assertEquals(wordCounts[newCh.title], map[newCh.getFileName()])
        }
    }

    @Test
    fun `整本下载跳过判断与读取口径一致`() {
        val dir = tmp.newFolder()
        val ch = chapter("唯一", index = 3)
        ch.bookUrl = "book1"
        ChapterTitleRegistry.update("book1", listOf("唯一"))
        // 未缓存 → 不跳过
        assertNull(resolveChapterCacheFile(dir, ch.getFileName(), ch.getLegacyFileName()))
        // 仅旧格式缓存存在（升级前下载）→ 与 getContent 一样判定已缓存，跳过重复下载
        File(dir, ch.getLegacyFileName()).writeText("old")
        assertNotNull(resolveChapterCacheFile(dir, ch.getFileName(), ch.getLegacyFileName()))
    }
}
