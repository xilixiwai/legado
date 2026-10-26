package io.legado.app.data.dao

import io.legado.app.help.book.BookUrlMove
import io.legado.app.help.book.BookUrlUpdate
import io.legado.app.help.book.DomainReplaceCounts
import io.legado.app.help.book.DomainReplaceExecutor
import io.legado.app.help.book.DomainReplacePlan
import io.legado.app.help.book.DomainReplaceStatement
import io.legado.app.help.book.UrlDomainReplacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * 「批量替换域名」的**事务与回滚回归测试**（真实 SQLite）。
 *
 * 每条用例都对应一条硬性要求：
 * 1. 成功提交后 books.bookUrl / tocUrl、chapters.url、chapters.bookUrl 三处一致；
 * 2. 书籍主键冲突 → 整批失败并回滚，不留半完成状态；
 * 3. 章节主键 `(url, bookUrl)` 冲突 → 同上；
 * 4. 事务中途抛异常 → 之前已执行的更新全部回滚；
 * 5. `PRAGMA defer_foreign_keys` 只是把外键检查推迟到提交，**不是关闭约束**；
 * 6. 预览过期（数据在执行前被改）→ 重新预检能拦住；即使拦不住，SQLite 约束也会兜底；
 * 7. 计数全部来自语句实际影响行数，异常时不返回「部分成功」；
 * 8. 不删除章节、不改阅读进度与书源绑定。
 */
class DomainReplaceTransactionTest {

    private lateinit var conn: Connection

    private val oldBookUrl = "http://old.com/book/1"
    private val newBookUrl = "http://new.com/book/1"

    @Before
    fun setUp() {
        conn = RealSqliteSupport.open()
        conn.insertBook(
            mapOf(
                "bookUrl" to oldBookUrl,
                "tocUrl" to "$oldBookUrl/toc",
                "name" to "测试书",
                "author" to "测试作者",
                "origin" to "https://source.one",
                "originName" to "源一",
                "durChapterIndex" to "5",
                "durChapterPos" to "100",
                "durChapterTitle" to "第五章",
                "group" to "2",
                "totalChapterNum" to "2",
            )
        )
        listOf("http://old.com/ch/0.html", "http://old.com/ch/1.html")
            .forEachIndexed { index, url ->
                conn.insertChapter(
                    mapOf(
                        "url" to url,
                        "title" to "第${index}章",
                        "isVolume" to "0",
                        "baseUrl" to "http://old.com",
                        "bookUrl" to oldBookUrl,
                        "index" to index.toString(),
                        "isVip" to "0",
                        "isPay" to "0",
                    )
                )
            }
    }

    @After
    fun tearDown() {
        conn.close()
    }

    // ------------------------------------------------------------------ 1. 成功路径

    @Test
    fun `成功提交后书籍目录章节三处地址一致`() {
        val chapterCountBefore = conn.scalar("select count(*) from chapters")
        val counts = executePlan(buildPlan())

        assertEquals(2, counts.chapterUrl)
        assertEquals(2, counts.chapterBaseUrl)
        assertEquals(1, counts.books)
        assertEquals(2, counts.chapterBookUrl)

        assertEquals(newBookUrl, single("select bookUrl from books"))
        assertEquals("$newBookUrl/toc", single("select tocUrl from books"))
        assertEquals(
            listOf("http://new.com/ch/0.html", "http://new.com/ch/1.html"),
            conn.strings("select url from chapters order by `index`")
        )
        assertEquals(
            listOf("http://new.com", "http://new.com"),
            conn.strings("select baseUrl from chapters order by `index`")
        )
        // 章节归属同步到新主键
        assertEquals(
            listOf(newBookUrl, newBookUrl),
            conn.strings("select bookUrl from chapters order by `index`")
        )
        // 不删章节
        assertEquals(chapterCountBefore, conn.scalar("select count(*) from chapters"))
        // 外键在提交时仍然成立
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
        // 进度与来源不动
        assertEquals(
            "https://source.one|源一|5|100|第五章|2",
            single(
                "select origin||'|'||originName||'|'||durChapterIndex||'|'||durChapterPos||'|'||" +
                        "durChapterTitle||'|'||`group` from books"
            )
        )
    }

    // ------------------------------------------------------------------ 2. 书籍主键冲突

    @Test
    fun `书籍主键冲突时整批回滚`() {
        // 书架上已经有一本占用了目标主键的书
        conn.insertBook(
            mapOf(
                "bookUrl" to newBookUrl,
                "tocUrl" to "$newBookUrl/toc",
                "name" to "占用者",
                "author" to "别人",
            )
        )

        val error = assertThrows(SQLException::class.java) { executePlan(buildPlan()) }
        assertTrue("实际错误：${error.message}", error.message!!.contains("UNIQUE", true))

        // 章节地址是「先于」书籍主键更新的，必须一起回滚
        assertEquals(
            listOf("http://old.com/ch/0.html", "http://old.com/ch/1.html"),
            conn.strings("select url from chapters order by `index`")
        )
        assertEquals(
            listOf("http://old.com", "http://old.com"),
            conn.strings("select baseUrl from chapters order by `index`")
        )
        assertEquals(
            listOf(oldBookUrl, oldBookUrl),
            conn.strings("select bookUrl from chapters order by `index`")
        )
        // 旧书还在原位，占用者也没被动过
        assertEquals(2, conn.scalar("select count(*) from books"))
        assertEquals(oldBookUrl, single("select bookUrl from books where name = '测试书'"))
        assertEquals(newBookUrl, single("select bookUrl from books where name = '占用者'"))
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 3. 章节主键冲突

    @Test
    fun `章节主键冲突时整批回滚`() {
        // 同一本书里已存在「新域名版本」的同一章 → 改写后会撞 (url, bookUrl) 主键
        conn.insertChapter(
            mapOf(
                "url" to "http://new.com/ch/0.html",
                "title" to "重复章",
                "isVolume" to "0",
                "baseUrl" to "http://new.com",
                "bookUrl" to oldBookUrl,
                "index" to "9",
                "isVip" to "0",
                "isPay" to "0",
            )
        )

        // 预检能发现
        val change = buildPlan().urlChanges.single()
        assertEquals(
            1,
            conn.scalar(
                DomainReplaceSql.COUNT_CHAPTER_URL_CONFLICTS,
                mapOf("prefix" to change.prefix, "newPrefix" to change.newPrefix)
            )
        )

        // 即使绕过预检直接写，SQLite 约束也会拦住
        val error = assertThrows(SQLException::class.java) { executePlan(buildPlan()) }
        assertTrue("实际错误：${error.message}", error.message!!.contains("UNIQUE", true))

        // 全部回滚：旧地址还在，注入的重复章也还在
        assertEquals(
            listOf(
                "http://old.com/ch/0.html",
                "http://old.com/ch/1.html",
                "http://new.com/ch/0.html",
            ),
            conn.strings("select url from chapters order by `index`")
        )
        assertEquals(oldBookUrl, single("select bookUrl from books"))
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 4. 中途异常

    @Test
    fun `事务中途异常时已执行的更新全部回滚`() {
        val plan = buildPlan()
        var executed = 0

        conn.autoCommit = false
        val error = assertThrows(IllegalStateException::class.java) {
            try {
                DomainReplaceExecutor.run(
                    plan = plan,
                    execSql = { conn.execRaw(it) },
                    apply = { statement ->
                        executed++
                        // 第 3 条（resourceUrl 之后的第一条 books 更新）注入异常
                        if (executed == 3) throw IllegalStateException("注入异常")
                        applyStatement(statement)
                    }
                )
            } finally {
                conn.rollback()
                conn.autoCommit = true
            }
        }
        assertEquals("注入异常", error.message)
        assertEquals(3, executed)

        // 前两条 UPDATE 已实际执行过，必须被回滚
        assertEquals(
            listOf("http://old.com/ch/0.html", "http://old.com/ch/1.html"),
            conn.strings("select url from chapters order by `index`")
        )
        assertEquals(
            listOf("http://old.com", "http://old.com"),
            conn.strings("select baseUrl from chapters order by `index`")
        )
        assertEquals(oldBookUrl, single("select bookUrl from books"))
    }

    @Test
    fun `异常时不返回部分成功的计数`() {
        val plan = buildPlan()
        var counts = DomainReplaceCounts()
        var abortReason: String? = null
        var executed = 0

        // 与 ViewModel 的 runDomainReplace 结构一致：counts 只在 run 正常返回时才被赋值
        conn.autoCommit = false
        try {
            counts = DomainReplaceExecutor.run(
                plan = plan,
                execSql = { conn.execRaw(it) },
                apply = { statement ->
                    executed++
                    if (executed == 2) throw SQLException("模拟写入失败")
                    applyStatement(statement)
                }
            )
            conn.commit()
        } catch (e: Throwable) {
            conn.rollback()
            abortReason = e.message
        } finally {
            conn.autoCommit = true
        }

        assertNotNull(abortReason)
        // 没有「部分成功」：计数仍是默认全零
        assertEquals(DomainReplaceCounts(), counts)
        assertEquals(
            listOf("http://old.com/ch/0.html", "http://old.com/ch/1.html"),
            conn.strings("select url from chapters order by `index`")
        )
    }

    // ------------------------------------------------------------------ 5. 外键：推迟检查 ≠ 关闭约束

    @Test
    fun `不推迟外键时改书籍主键会被立即拒绝`() {
        conn.autoCommit = false
        try {
            val error = assertThrows(SQLException::class.java) {
                conn.exec(
                    DomainReplaceSql.UPDATE_BOOK_URLS,
                    mapOf(
                        "oldBookUrl" to oldBookUrl,
                        "newBookUrl" to newBookUrl,
                        "newTocUrl" to "$newBookUrl/toc",
                    )
                )
            }
            assertTrue(
                "实际错误：${error.message}",
                error.message!!.contains("FOREIGN KEY", true)
            )
        } finally {
            conn.rollback()
            conn.autoCommit = true
        }
        assertEquals(oldBookUrl, single("select bookUrl from books"))
    }

    @Test
    fun `推迟外键但漏同步章节归属时提交仍会失败`() {
        conn.autoCommit = false
        try {
            conn.execRaw(DomainReplaceExecutor.DEFER_FOREIGN_KEYS)
            conn.exec(
                DomainReplaceSql.UPDATE_BOOK_URLS,
                mapOf(
                    "oldBookUrl" to oldBookUrl,
                    "newBookUrl" to newBookUrl,
                    "newTocUrl" to "$newBookUrl/toc",
                )
            )
            // 语句级通过，但外键此刻确实悬空
            assertTrue(conn.foreignKeyViolations().isNotEmpty())

            // 提交时外键仍会校验 —— 证明 defer 只是推迟，不是关闭
            val error = assertThrows(SQLException::class.java) { conn.commit() }
            assertTrue(
                "实际错误：${error.message}",
                error.message!!.contains("FOREIGN KEY", true)
            )
        } finally {
            runCatching { conn.rollback() }
            conn.autoCommit = true
        }
        assertEquals(oldBookUrl, single("select bookUrl from books"))
        assertEquals(
            listOf(oldBookUrl, oldBookUrl),
            conn.strings("select bookUrl from chapters order by `index`")
        )
    }

    @Test
    fun `推迟外键并同步章节归属后提交成功`() {
        val counts = executePlan(buildPlan())
        assertEquals(1, counts.books)
        assertEquals(2, counts.chapterBookUrl)
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 6. 预览过期

    @Test
    fun `预览过期后执行前重新预检能拦住`() {
        // 预览时刻：没有冲突
        val change = buildPlan().urlChanges.single()
        assertEquals(
            0,
            conn.scalar(
                DomainReplaceSql.COUNT_CHAPTER_URL_CONFLICTS,
                mapOf("prefix" to change.prefix, "newPrefix" to change.newPrefix)
            )
        )

        // 预览之后、执行之前，另一个流程写入了新域名版本的同名章节
        conn.insertChapter(
            mapOf(
                "url" to "http://new.com/ch/0.html",
                "title" to "他处写入",
                "isVolume" to "0",
                "baseUrl" to "http://new.com",
                "bookUrl" to oldBookUrl,
                "index" to "8",
                "isVip" to "0",
                "isPay" to "0",
            )
        )

        // 执行前重新预检 → 能发现
        assertEquals(
            1,
            conn.scalar(
                DomainReplaceSql.COUNT_CHAPTER_URL_CONFLICTS,
                mapOf("prefix" to change.prefix, "newPrefix" to change.newPrefix)
            )
        )

        // 即使跳过预检直接写，约束兜底 + 回滚，数据保持原样
        assertThrows(SQLException::class.java) { executePlan(buildPlan()) }
        assertEquals(
            listOf(
                "http://old.com/ch/0.html",
                "http://old.com/ch/1.html",
                "http://new.com/ch/0.html",
            ),
            conn.strings("select url from chapters order by `index`")
        )
        assertEquals(oldBookUrl, single("select bookUrl from books"))
    }

    // ------------------------------------------------------------------ 内部

    private fun single(sql: String): String = conn.strings(sql).single()

    private fun buildPlan(): DomainReplacePlan {
        val rule = when (val r = UrlDomainReplacement.normalize("old.com", "new.com")) {
            is UrlDomainReplacement.NormalizeResult.Success -> r.rule
            is UrlDomainReplacement.NormalizeResult.Failure -> throw AssertionError(r.reason.name)
        }
        val urlChanges =
            DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_URL_GROUPS), rule)
        val baseUrlChanges =
            DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_BASE_URL_GROUPS), rule)
        val resourceUrlChanges = DomainReplaceExecutor.changes(
            conn.groups(DomainReplaceSql.CHAPTER_RESOURCE_URL_GROUPS),
            rule
        )
        val bookUpdates = mutableListOf<BookUrlUpdate>()
        val bookMoves = mutableListOf<BookUrlMove>()
        val bookUrls = conn.strings("select bookUrl from books order by bookUrl")
        val tocUrls = conn.strings("select tocUrl from books order by bookUrl")
        bookUrls.indices.forEach { i ->
            val oldBookUrl = bookUrls[i]
            val oldTocUrl = tocUrls[i]
            val replaced =
                UrlDomainReplacement.replaceBookUrls(oldBookUrl, oldTocUrl, rule) ?: return@forEach
            val newBookUrl = replaced.bookUrl ?: oldBookUrl
            val newTocUrl = replaced.tocUrl ?: oldTocUrl
            bookUpdates.add(BookUrlUpdate(oldBookUrl, newBookUrl, newTocUrl))
            if (newBookUrl != oldBookUrl) bookMoves.add(BookUrlMove(oldBookUrl, newBookUrl))
        }
        return DomainReplacePlan(
            urlChanges = urlChanges,
            baseUrlChanges = baseUrlChanges,
            resourceUrlChanges = resourceUrlChanges,
            bookUpdates = bookUpdates,
            bookMoves = bookMoves,
        )
    }

    private fun applyStatement(statement: DomainReplaceStatement): Int {
        return when (statement) {
            is DomainReplaceStatement.ChapterUrl -> conn.exec(
                DomainReplaceSql.REPLACE_CHAPTER_URL_DOMAIN,
                mapOf("prefix" to statement.prefix, "newPrefix" to statement.newPrefix)
            )

            is DomainReplaceStatement.ChapterBaseUrl -> conn.exec(
                DomainReplaceSql.REPLACE_CHAPTER_BASE_URL_DOMAIN,
                mapOf("prefix" to statement.prefix, "newPrefix" to statement.newPrefix)
            )

            is DomainReplaceStatement.ChapterResourceUrl -> conn.exec(
                DomainReplaceSql.REPLACE_CHAPTER_RESOURCE_URL_DOMAIN,
                mapOf("prefix" to statement.prefix, "newPrefix" to statement.newPrefix)
            )

            is DomainReplaceStatement.BookUrls -> conn.exec(
                DomainReplaceSql.UPDATE_BOOK_URLS,
                mapOf(
                    "oldBookUrl" to statement.oldBookUrl,
                    "newBookUrl" to statement.newBookUrl,
                    "newTocUrl" to statement.newTocUrl,
                )
            )

            is DomainReplaceStatement.ChapterBookUrl -> conn.exec(
                DomainReplaceSql.REPLACE_CHAPTER_BOOK_URL,
                mapOf(
                    "oldBookUrl" to statement.oldBookUrl,
                    "newBookUrl" to statement.newBookUrl,
                )
            )
        }
    }

    private fun executePlan(plan: DomainReplacePlan): DomainReplaceCounts {
        conn.autoCommit = false
        try {
            val counts = DomainReplaceExecutor.run(
                plan = plan,
                execSql = { conn.execRaw(it) },
                apply = { applyStatement(it) },
            )
            conn.commit()
            return counts
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
    }
}
