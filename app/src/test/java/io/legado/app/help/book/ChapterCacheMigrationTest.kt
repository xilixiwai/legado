package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.MD5Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 旧版章节正文缓存迁移测试（真实临时目录读写，不 mock 文件系统）。
 *
 * 覆盖：安全分类（唯一标题/重复标题/竞争候选/孤儿/异常）、执行保护（不覆盖目标、
 * 失败保留原文件、单文件失败不影响其他）、中断恢复（重新扫描不重复迁移）、
 * 跨书隔离、非默认 suffix、迁移不改动章节数据。
 */
class ChapterCacheMigrationTest {

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

    private fun legacyName(title: String, index: Int) =
        String.format("%05d-%s.nb", index, md5(title))

    private fun ch(
        title: String,
        index: Int,
        url: String = "https://a.b/c$index.html",
        bookUrl: String = "book1"
    ) = BookChapter(url = url, title = title, index = index, bookUrl = bookUrl)

    private fun writeLegacy(dir: File, title: String, index: Int, content: String) =
        File(dir, legacyName(title, index)).apply { writeText(content) }

    // ============================================================================================
    // 1. 唯一标题 + index 精确匹配 → 迁移成功
    // ============================================================================================

    @Test
    fun `唯一标题且index匹配时迁移成功`() {
        val dir = tmp.newFolder("book1")
        writeLegacy(dir, "第一章", 0, "第一章的正文")
        val chapters = listOf(ch("第一章", 0))
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, plan.safe)
        assertEquals(1, plan.total)

        val stats = ChapterCacheMigrator.execute(plan, includeMoved = false)
        assertEquals(1, stats.renamed)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.failed)

        // 目标名与第一阶段 getFileName() 一致，内容完整，旧文件消失
        val target = File(dir, chapters[0].getFileName())
        assertTrue(target.isFile)
        assertEquals("第一章的正文", target.readText())
        assertFalse(File(dir, legacyName("第一章", 0)).exists())
    }

    // ============================================================================================
    // 2. 目录重排后 index 不匹配 → 默认保留，显式确认后才迁移
    // ============================================================================================

    @Test
    fun `index不匹配时默认保留且确认后迁移`() {
        val dir = tmp.newFolder("book1")
        writeLegacy(dir, "第一章", 5, "旧序号下的正文")
        val chapters = listOf(ch("第一章", 2))
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(0, plan.safe)
        assertEquals(1, plan.uniqueMoved)

        // 默认不迁移，原文件保留
        val statsDefault = ChapterCacheMigrator.execute(plan, includeMoved = false)
        assertEquals(0, statsDefault.renamed)
        assertTrue(File(dir, legacyName("第一章", 5)).exists())
        assertFalse(File(dir, chapters[0].getFileName()).exists())

        // 用户显式确认后迁移，正文内容完整转移到新键
        val statsOptIn = ChapterCacheMigrator.execute(plan, includeMoved = true)
        assertEquals(1, statsOptIn.renamed)
        val target = File(dir, chapters[0].getFileName())
        assertTrue(target.isFile)
        assertEquals("旧序号下的正文", target.readText())
        assertFalse(File(dir, legacyName("第一章", 5)).exists())
    }

    // ============================================================================================
    // 3. 重复标题不会错误合并
    // ============================================================================================

    @Test
    fun `重复标题章节不会错误合并`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("番外", 1), ch("番外", 3))
        // 两个 twin 的旧文件（index 各自匹配）：已在（等同）新键位，无需迁移也不移动
        writeLegacy(dir, "番外", 1, "twin1")
        writeLegacy(dir, "番外", 3, "twin3")
        // 序号对不上的旧文件：无法证明属于哪个 twin，保留
        writeLegacy(dir, "番外", 9, "unknown")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(2, plan.alreadyNew)
        assertEquals(1, plan.duplicateKept)
        assertEquals(0, plan.safe)

        ChapterCacheMigrator.execute(plan, includeMoved = true)
        // 三个文件全部原地保留，没有合并到同一个文件
        assertEquals("twin1", File(dir, legacyName("番外", 1)).readText())
        assertEquals("twin3", File(dir, legacyName("番外", 3)).readText())
        assertEquals("unknown", File(dir, legacyName("番外", 9)).readText())
        // 重复标题的新键 = index-titleMD5，两个 twin 的键不同
        assertNotEquals(chapters[0].getFileName(), chapters[1].getFileName())
    }

    // ============================================================================================
    // 4. 多个旧文件竞争同一目标时不会随意选择
    // ============================================================================================

    @Test
    fun `多个旧文件竞争时不随意选择`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第1章", 3))
        // 同标题出现过两个旧文件（重复标题历史或多次重排残留），即使其一 index 匹配也无法证明归属
        writeLegacy(dir, "第1章", 3, "candidateA")
        writeLegacy(dir, "第1章", 8, "candidateB")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(0, plan.safe)
        assertEquals(2, plan.ambiguous)

        val stats = ChapterCacheMigrator.execute(plan, includeMoved = true)
        assertEquals(0, stats.renamed)
        assertEquals("candidateA", File(dir, legacyName("第1章", 3)).readText())
        assertEquals("candidateB", File(dir, legacyName("第1章", 8)).readText())
        assertFalse(File(dir, chapters[0].getFileName()).exists())
    }

    // ============================================================================================
    // 5/6. 目标已存在（含损坏）时不覆盖、不删旧文件
    // ============================================================================================

    @Test
    fun `目标已存在时不覆盖且保留旧文件`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0))
        writeLegacy(dir, "第一章", 0, "legacy")
        // 生产中此键由已加载目录保证；测试显式刷新注册表后取写入侧键名
        ChapterTitleRegistry.update("book1", chapters.map { it.title })
        File(dir, chapters[0].getFileName()).writeText("new")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, plan.targetExists)
        assertEquals(0, plan.safe)

        val stats = ChapterCacheMigrator.execute(plan, includeMoved = true)
        assertEquals(0, stats.renamed)
        assertEquals("new", File(dir, chapters[0].getFileName()).readText())
        assertEquals("legacy", File(dir, legacyName("第一章", 0)).readText())
    }

    @Test
    fun `目标损坏为空时不会删除旧文件`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0))
        writeLegacy(dir, "第一章", 0, "legacy")
        ChapterTitleRegistry.update("book1", chapters.map { it.title })
        File(dir, chapters[0].getFileName()).writeText("")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        // 空目标也不覆盖：迁移器不负责修复目标，旧文件保留并展示
        assertEquals(1, plan.targetExists)
        ChapterCacheMigrator.execute(plan, includeMoved = true)
        assertEquals("legacy", File(dir, legacyName("第一章", 0)).readText())
        assertEquals("", File(dir, chapters[0].getFileName()).readText())
    }

    // ============================================================================================
    // 7/8. 空文件与异常文件跳过并报告
    // ============================================================================================

    @Test
    fun `空文件被跳过且不阻塞同标题有效文件`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0), ch("第二章", 1))
        File(dir, legacyName("第一章", 0)).writeText("")
        writeLegacy(dir, "第二章", 1, "第二章的正文")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, plan.invalid)
        assertEquals(1, plan.safe)

        val stats = ChapterCacheMigrator.execute(plan, includeMoved = false)
        assertEquals(1, stats.renamed)
        // 空文件保留在原地
        assertTrue(File(dir, legacyName("第一章", 0)).exists())
        assertEquals("", File(dir, legacyName("第一章", 0)).readText())
    }

    @Test
    fun `异常形态文件被报告且不参与迁移`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 1))
        // 同名目录：文件名符合旧格式但不是常规文件
        File(dir, legacyName("第一章", 1)).mkdirs()
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, plan.invalid)
        assertEquals(0, plan.safe)

        val stats = ChapterCacheMigrator.execute(plan, includeMoved = true)
        assertEquals(0, stats.renamed)
        assertTrue(File(dir, legacyName("第一章", 1)).isDirectory)
    }

    // ============================================================================================
    // 9/12. 执行失败保留原文件；单文件失败不影响其他独立文件
    // ============================================================================================

    @Test
    fun `单文件失败保留原文件且不影响其他文件`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0), ch("第二章", 1), ch("第三章", 2))
        writeLegacy(dir, "第一章", 0, "c0")
        writeLegacy(dir, "第二章", 1, "c1")
        writeLegacy(dir, "第三章", 2, "c2")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(3, plan.safe)

        // 注入：第二章重命名失败（模拟底层 IO 失败）
        val stats = ChapterCacheMigrator.execute(
            plan,
            includeMoved = false,
            renameOp = { src, dst ->
                // 第二章模拟底层重命名失败，其余文件正常重命名
                !src.name.contains(md5("第二章")) && src.renameTo(dst)
            }
        )
        assertEquals(2, stats.renamed)
        assertEquals(1, stats.failed)
        // 失败文件原样保留
        assertEquals("c1", File(dir, legacyName("第二章", 1)).readText())
        assertFalse(File(dir, ch("第二章", 1).getFileName()).exists())
        // 成功文件正常迁移
        assertEquals("c0", File(dir, ch("第一章", 0).getFileName()).readText())
        assertEquals("c2", File(dir, ch("第三章", 2).getFileName()).readText())
    }

    // ============================================================================================
    // 10/11. 成功后重新扫描不重复迁移；中断后可继续处理剩余
    // ============================================================================================

    @Test
    fun `重新扫描不会重复迁移已成功文件且可继续处理剩余`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0), ch("第二章", 1))
        writeLegacy(dir, "第一章", 0, "c0")
        writeLegacy(dir, "第二章", 1, "c1")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)

        // 模拟中断：只处理了第一章就停止（直接调用单文件级执行）
        val partialPlan = plan.copy(items = plan.items.take(1))
        ChapterCacheMigrator.execute(partialPlan, includeMoved = false)
        assertTrue(File(dir, ch("第一章", 0).getFileName()).exists())
        assertTrue(File(dir, legacyName("第二章", 1)).exists())

        // 重新扫描：已迁移文件不重复出现（新键不是旧格式），剩余文件仍可识别并迁移
        val rescan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, rescan.total)
        assertEquals(1, rescan.safe)
        val stats = ChapterCacheMigrator.execute(rescan, includeMoved = false)
        assertEquals(1, stats.renamed)
        assertEquals("c0", File(dir, ch("第一章", 0).getFileName()).readText())
        assertEquals("c1", File(dir, ch("第二章", 1).getFileName()).readText())
        assertFalse(File(dir, legacyName("第一章", 0)).exists())
        assertFalse(File(dir, legacyName("第二章", 1)).exists())
    }

    // ============================================================================================
    // 13. 不会错误迁移到其他书籍缓存目录
    // ============================================================================================

    @Test
    fun `迁移不跨书籍目录`() {
        val dir1 = tmp.newFolder("book1")
        val dir2 = tmp.newFolder("book2")
        val chapters1 = listOf(ch("第一章", 0))
        val chapters2 = listOf(ch("第一章", 7))
        writeLegacy(dir1, "第一章", 0, "book1内容")
        writeLegacy(dir2, "第一章", 7, "book2内容")
        val plan1 = ChapterCacheMigrator.plan("book1", "书一", dir1, chapters1)
        val plan2 = ChapterCacheMigrator.plan("book2", "书二", dir2, chapters2)
        assertEquals(1, plan1.safe)
        assertEquals(1, plan2.safe)

        ChapterCacheMigrator.execute(plan1, includeMoved = false)
        // 只有 book1 目录发生迁移，book2 完全不受影响
        assertTrue(File(dir1, ch("第一章", 0).getFileName()).exists())
        assertFalse(File(dir1, legacyName("第一章", 0)).exists())
        assertFalse(File(dir2, ch("第一章", 7).getFileName()).exists())
        assertTrue(File(dir2, legacyName("第一章", 7)).exists())
        assertEquals("book2内容", File(dir2, legacyName("第一章", 7)).readText())
    }

    // ============================================================================================
    // 14. 非默认 suffix（nr 标记文件）不迁移、不删除
    // ============================================================================================

    @Test
    fun `非默认suffix标记文件不迁移也不删除`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0))
        writeLegacy(dir, "第一章", 0, "正文")
        File(dir, String.format("%05d-%s.nr", 0, md5("第一章"))).writeText("")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, plan.nrFlagFiles)
        assertEquals(1, plan.safe)
        assertEquals(1, plan.total)

        ChapterCacheMigrator.execute(plan, includeMoved = false)
        assertTrue(File(dir, String.format("%05d-%s.nr", 0, md5("第一章"))).exists())
        // 正文正常迁移
        assertTrue(File(dir, ch("第一章", 0).getFileName()).exists())
    }

    // ============================================================================================
    // 15. 迁移不改变章节数据（进度/数据库字段不在迁移范围）
    // ============================================================================================

    @Test
    fun `迁移不修改章节对象数据`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0), ch("第二章", 1))
        writeLegacy(dir, "第一章", 0, "c0")
        val snapshot = chapters.map { Triple(it.title, it.index, it.url) }
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        ChapterCacheMigrator.execute(plan, includeMoved = true)
        chapters.forEachIndexed { i, chapter ->
            assertEquals(snapshot[i].first, chapter.title)
            assertEquals(snapshot[i].second, chapter.index)
            assertEquals(snapshot[i].third, chapter.url)
        }
    }

    // ============================================================================================
    // 补充：孤儿文件保留；卷名不参与匹配；目标名与写入侧一致
    // ============================================================================================

    @Test
    fun `孤儿文件保留且不参与迁移`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0))
        // 章节标题已改：旧文件 MD5 对不上任何当前章节
        writeLegacy(dir, "旧标题", 0, "历史残留")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, plan.orphan)
        assertEquals(0, plan.safe)
        ChapterCacheMigrator.execute(plan, includeMoved = true)
        assertTrue(File(dir, legacyName("旧标题", 0)).exists())
        assertEquals("历史残留", File(dir, legacyName("旧标题", 0)).readText())
    }

    @Test
    fun `卷名章节不参与归属匹配`() {
        val dir = tmp.newFolder("book1")
        val volume = BookChapter(
            url = "第一卷", title = "第一卷", index = 0, bookUrl = "book1", isVolume = true
        )
        val chapters = listOf(volume, ch("第一章", 1))
        // 旧文件 MD5 恰好等于卷名 MD5：卷名从不写正文，无法归属到章节，保留
        writeLegacy(dir, "第一卷", 0, "orphan")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        assertEquals(1, plan.orphan)
        assertEquals(1, plan.total)
    }

    @Test
    fun `迁移目标与BookHelp写入侧文件名一致`() {
        val dir = tmp.newFolder("book1")
        val chapters = listOf(ch("第一章", 0), ch("同标题", 2), ch("同标题", 5))
        writeLegacy(dir, "第一章", 0, "c0")
        writeLegacy(dir, "同标题", 2, "c2")
        val plan = ChapterCacheMigrator.plan("book1", "书一", dir, chapters)
        // 唯一标题迁移后的文件名 = getFileName()（BookHelp.saveText 的写入键）
        val safeItem = plan.items.first { it.action == ChapterCacheMigrator.Action.RENAME_SAFE }
        assertEquals(ch("第一章", 0).getFileName(), safeItem.targetName)
        // 重复标题 index 匹配 = 已是新键（与 getFileName 相同）
        assertEquals(
            ch("同标题", 2).getFileName(),
            String.format("%05d-%s.nb", 2, md5("同标题"))
        )
        ChapterCacheMigrator.execute(plan, includeMoved = false)
        assertTrue(File(dir, ch("第一章", 0).getFileName()).exists())
    }

    private fun assertNotEquals(a: Any, b: Any) {
        assertFalse("expected different values", a == b)
    }
}
