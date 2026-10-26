package io.legado.app.help.book

import io.legado.app.help.book.DomainReplaceConflicts.ConflictKind
import io.legado.app.help.book.DomainReplaceConflicts.ConflictBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「批量替换域名」冲突分类与处理规则的纯逻辑测试。
 *
 * 覆盖：路径/证据函数、分类规则（A1 已确认 / 证据不足 / 不同源 / 不同书 / 本地书 /
 * 批内碰撞）、进度按章节路径映射、章节去重后的阅读位置修正。
 */
class DomainReplaceConflictsTest {

    // ------------------------------------------------------------------ 路径工具

    @Test
    fun `章节路径跨域名稳定`() {
        assertEquals(
            "/book/1/ch/0.html",
            DomainReplaceConflicts.pathOf("https://old.com/book/1/ch/0.html")
        )
        assertEquals(
            "/book/1/ch/0.html",
            DomainReplaceConflicts.pathOf("http://new.com:8080/book/1/ch/0.html")
        )
        assertNull(DomainReplaceConflicts.pathOf("/local/file.txt"))
        assertNull(DomainReplaceConflicts.pathOf(""))
    }

    @Test
    fun `书名一致判定忽略大小写与首尾空白`() {
        assertTrue(DomainReplaceConflicts.nameMatches(" 斗破苍穹 ", "斗破苍穹"))
        assertTrue(DomainReplaceConflicts.nameMatches("ABC", "abc"))
        assertFalse(DomainReplaceConflicts.nameMatches("斗破苍穹", "斗破苍穹（校对版）"))
        assertFalse(DomainReplaceConflicts.nameMatches("我有一剑", "我有一剑2"))
    }

    @Test
    fun `bookUrl 路径相同是同书证据`() {
        assertTrue(
            DomainReplaceConflicts.bookUrlPathMatches(
                "https://old.com/book/123",
                "https://new.com/book/123"
            )
        )
        assertFalse(
            DomainReplaceConflicts.bookUrlPathMatches(
                "https://old.com/book/123",
                "https://new.com/book/456"
            )
        )
    }

    @Test
    fun `章节路径重叠证据阈值`() {
        val old = (0 until 10).map { "/b/1/c$it.html" }.toSet()
        val targetFull = (0 until 10).map { "/b/1/c$it.html" }.toSet()
        val targetHalf = (0 until 5).map { "/b/1/c$it.html" }.toSet()
        val targetNone = (100 until 105).map { "/b/1/c$it.html" }.toSet()
        assertTrue(DomainReplaceConflicts.chapterEvidenceEnough(old, targetFull))
        assertTrue(DomainReplaceConflicts.chapterEvidenceEnough(old, targetHalf))
        // 重叠 0 条：不算证据
        assertFalse(DomainReplaceConflicts.chapterEvidenceEnough(old, targetNone))
        // 任何一边没有章节：不算证据
        assertFalse(DomainReplaceConflicts.chapterEvidenceEnough(emptySet(), targetFull))
        // 重叠率过低：不算证据（2/10）
        val targetWeak = (0 until 2).map { "/b/1/c$it.html" }.toSet()
        assertFalse(DomainReplaceConflicts.chapterEvidenceEnough(old, targetWeak))
    }

    // ------------------------------------------------------------------ 分类规则

    private fun book(
        url: String,
        name: String,
        author: String,
        origin: String,
        isLocal: Boolean = false,
        paths: Set<String> = emptySet(),
    ) = ConflictBook(
        bookUrl = url,
        name = name,
        author = author,
        origin = origin,
        isLocal = isLocal,
        chapterCount = paths.size,
        chapterPaths = paths,
        hasProgress = false,
    )

    @Test
    fun `A1_同源同名且章节路径重叠_确认为同一本书`() {
        val old = book(
            "https://old.com/book/1", "我有一剑", "青鸾峰上", "https://source.example.com/a",
            paths = (0 until 10).map { "/book/1/c$it.html" }.toSet(),
        )
        val target = book(
            "https://new.com/book/1", "我有一剑", "", "https://source.example.com/a",
            paths = (0 until 8).map { "/book/1/c$it.html" }.toSet(),
        )
        assertEquals(
            ConflictKind.SAME_BOOK_CONFIRMED,
            DomainReplaceConflicts.classify(old, target, batchCollides = false)
        )
    }

    @Test
    fun `A1_同源同名且bookUrl路径相同_确认为同一本书`() {
        val old = book("https://old.com/book/1", "我有一剑", "青鸾峰上", "https://source.example.com/a")
        val target = book("https://new.com/book/1", "我有一剑", "青鸾峰上", "https://source.example.com/a")
        assertEquals(
            ConflictKind.SAME_BOOK_CONFIRMED,
            DomainReplaceConflicts.classify(old, target, batchCollides = false)
        )
    }

    @Test
    fun `同源同名但无任何路径证据_只标记疑似_不自动处理`() {
        val old = book("https://old.com/book/1", "我有一剑", "青鸾峰上", "https://source.example.com/a")
        val target = book("https://new.com/book/9", "我有一剑", "", "https://source.example.com/a")
        assertEquals(
            ConflictKind.SAME_BOOK_UNCONFIRMED,
            DomainReplaceConflicts.classify(old, target, batchCollides = false)
        )
    }

    @Test
    fun `同名但不同书源_交给用户`() {
        val old = book("https://old.com/book/1", "我有一剑", "青鸾峰上", "https://source-a.com")
        val target = book("https://new.com/book/1", "我有一剑", "青鸾峰上", "https://source-b.com")
        assertEquals(
            ConflictKind.SAME_NAME_DIFF_SOURCE,
            DomainReplaceConflicts.classify(old, target, batchCollides = false)
        )
    }

    @Test
    fun `同名不同作者即不同书_即使同书源`() {
        // 同书源、同名、不同作者：书名相同作者不同可能是同源上的两本不同书，
        // 没有章节证据时绝不能自动判定为同一本
        val old = book("https://old.com/book/1", "斗破苍穹", "天蚕土豆", "https://source.example.com/a")
        val target = book(
            "https://new.com/book/7", "斗破苍穹", "另一个人", "https://source.example.com/a",
            paths = (0 until 5).map { "/other/c$it.html" }.toSet(),
        )
        assertEquals(
            ConflictKind.SAME_NAME_DIFF_SOURCE,
            DomainReplaceConflicts.classify(old, target, batchCollides = false)
        )
    }

    @Test
    fun `书名不同即不同书`() {
        val old = book("https://old.com/book/1", "书A", "作者A", "https://source.example.com/a")
        val target = book("https://new.com/book/1", "书B", "作者B", "https://source.example.com/a")
        assertEquals(
            ConflictKind.DIFF_BOOK,
            DomainReplaceConflicts.classify(old, target, batchCollides = false)
        )
    }

    @Test
    fun `目标是本地书籍时绝不自动处理`() {
        val old = book("https://old.com/book/1", "书A", "作者A", "https://source.example.com/a")
        val target = book(
            "https://new.com/book/1", "书A", "作者A", "loc_book",
            isLocal = true,
            paths = (0 until 10).map { "/book/1/c$it.html" }.toSet(),
        )
        assertEquals(
            ConflictKind.TARGET_IS_LOCAL,
            DomainReplaceConflicts.classify(old, target, batchCollides = false)
        )
    }

    @Test
    fun `批内多本书指向同一目标_标记批内碰撞`() {
        val old = book("https://old.com/book/1", "书A", "作者A", "https://source.example.com/a")
        val target = book("https://new.com/book/1", "书A", "作者A", "https://source.example.com/a")
        assertEquals(
            ConflictKind.BATCH_TARGET_COLLISION,
            DomainReplaceConflicts.classify(old, target, batchCollides = true)
        )
    }

    // ------------------------------------------------------------------ 进度映射

    @Test
    fun `阅读位置按章节路径映射到新目录`() {
        val oldChapters = listOf(
            "https://old.com/b/1/c0.html",
            "https://old.com/b/1/c1.html",
            "https://old.com/b/1/c2.html",
        )
        val targetPaths = listOf(
            "/b/1/c0.html", "/b/1/c1.html", "/b/1/c2.html",
        )
        assertEquals(
            1,
            DomainReplaceConflicts.mapProgressByPath(1, oldChapters, targetPaths)
        )
        // 新目录前部插入了一卷：路径映射仍指向正确章节
        val targetWithVolume = listOf("/b/1/vol", "/b/1/c0.html", "/b/1/c1.html", "/b/1/c2.html")
        assertEquals(
            3,
            DomainReplaceConflicts.mapProgressByPath(2, oldChapters, targetWithVolume)
        )
        assertNull(DomainReplaceConflicts.mapProgressByPath(0, oldChapters, listOf("/x/y.html")))
        assertNull(DomainReplaceConflicts.mapProgressByPath(9, oldChapters, targetPaths))
    }

    // ------------------------------------------------------------------ 章节去重后的进度修正

    @Test
    fun `去重后进度位置按幸存章节重算`() {
        // 同一本书既有旧域名行又有新域名行（部分迁移状态）
        val ordered = listOf(
            "https://old.com/b/1/c0.html",   // 将被删除（c0 的新域名行已存在）
            "https://new.com/b/1/c0.html",
            "https://old.com/b/1/c1.html",   // 将被删除
            "https://new.com/b/1/c1.html",
            "https://old.com/b/1/c2.html",
        )
        val deleted = setOf(
            "https://old.com/b/1/c0.html",
            "https://old.com/b/1/c1.html",
        )
        // 进度在第 4 行（new c1），删除前位置 3；删除两行在前 → 幸存序列 [new c0, new c1, old c2]，位置 1
        assertEquals(
            1 to "https://new.com/b/1/c1.html",
            DomainReplaceConflicts.adjustProgressAfterDedupe(3, ordered, deleted)
        )
        // 进度指向被删除的旧 c0 → 对应保留的 new c0，位置 0
        assertEquals(
            0 to "https://new.com/b/1/c0.html",
            DomainReplaceConflicts.adjustProgressAfterDedupe(0, ordered, deleted)
        )
        // 无删除：位置不变
        assertEquals(
            2 to ordered[2],
            DomainReplaceConflicts.adjustProgressAfterDedupe(2, ordered, emptySet())
        )
        assertNull(DomainReplaceConflicts.adjustProgressAfterDedupe(99, ordered, deleted))
    }
}
