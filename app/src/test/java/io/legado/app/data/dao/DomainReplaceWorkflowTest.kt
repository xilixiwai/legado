package io.legado.app.data.dao

import io.legado.app.help.book.BookUrlMove
import io.legado.app.help.book.BookUrlUpdate
import io.legado.app.help.book.DomainReplaceConflicts
import io.legado.app.help.book.DomainReplaceCounts
import io.legado.app.help.book.DomainReplaceExecutor
import io.legado.app.help.book.DomainReplacePlan
import io.legado.app.help.book.DomainReplaceStatement
import io.legado.app.help.book.UrlDomainReplacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * 「批量替换域名」冲突工作流的**真实 SQLite 回归**。
 *
 * 覆盖本轮核心行为（ViewModel 层的分区与分类逻辑由纯逻辑测试覆盖，
 * 这里用与 ViewModel 完全相同的生产 SQL + 执行顺序验证数据库侧语义）：
 * 1. 无冲突时全部替换（去重语句零行，不改变行为）；
 * 2. 干净记录与冲突记录混合时，普通记录仍然完成，冲突书籍保持原样；
 * 3. 章节主键冲突自动去重（删除旧域名重复行、保留新域名行）；
 * 4. 去重后阅读位置修正与真实章节序列一致；
 * 5. A1 清理：删旧书级联删章节、进度/分组/书签/阅读统计迁移、目标章节不动；
 * 6. 本地书籍受保护：(name,author) 唯一索引事实 + 非 http 地址不被命中；
 * 7. 同名不同书不被合并或删除；
 * 8. 单条处理失败不回滚已提交的其他结果；
 * 9. 冲突消失后单本完成替换（作用域 SQL）；
 * 10. 已完成的记录重查为零（幂等）；提交后重查与统计一致。
 */
class DomainReplaceWorkflowTest {

    private lateinit var conn: Connection

    private val rule = when (val r = UrlDomainReplacement.normalize("https://old.com", "https://new.com")) {
        is UrlDomainReplacement.NormalizeResult.Success -> r.rule
        is UrlDomainReplacement.NormalizeResult.Failure -> throw AssertionError(r.reason.name)
    }

    @Before
    fun setUp() {
        conn = RealSqliteSupport.open()
        // 书A：目标空闲，可干净替换
        insertBook("https://old.com/book/1", "https://old.com/book/1/toc", "书A", "作者A")
        insertChapters(
            "https://old.com/book/1",
            listOf("https://old.com/ch/a0.html", "https://old.com/ch/a1.html"),
            "https://old.com",
        )
        // 书B：替换目标被书X占用（不同书）
        insertBook("https://old.com/book/2", "https://old.com/book/2/toc", "书B", "作者B")
        insertChapters(
            "https://old.com/book/2",
            listOf("https://old.com/ch/b0.html", "https://old.com/ch/b1.html"),
            "https://old.com",
        )
        insertBook("https://new.com/book/2", "https://new.com/book/2/toc", "书X", "作者X")
        insertChapters(
            "https://new.com/book/2",
            listOf("https://new.com/ch/x0.html"),
            "https://new.com",
        )
    }

    @After
    fun tearDown() {
        conn.close()
    }

    // ------------------------------------------------------------------ 种子工具

    private fun insertBook(bookUrl: String, tocUrl: String, name: String, author: String) {
        conn.insertBook(
            mapOf(
                "bookUrl" to bookUrl,
                "tocUrl" to tocUrl,
                "name" to name,
                "author" to author,
                "origin" to "https://source.example.com/a",
                "originName" to "源A",
            )
        )
    }

    private fun insertChapters(bookUrl: String, urls: List<String>, baseUrl: String) {
        urls.forEachIndexed { index, url ->
            conn.insertChapter(
                mapOf(
                    "url" to url,
                    "title" to "第${index}章",
                    "isVolume" to "0",
                    "baseUrl" to baseUrl,
                    "bookUrl" to bookUrl,
                    "index" to index.toString(),
                    "isVip" to "0",
                    "isPay" to "0",
                )
            )
        }
    }

    private fun buildPlan(excludeBookUrls: Set<String> = emptySet()): DomainReplacePlan {
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
            val replaced =
                UrlDomainReplacement.replaceBookUrls(bookUrls[i], tocUrls[i], rule)
                    ?: return@forEach
            if (bookUrls[i] in excludeBookUrls) return@forEach
            val newBookUrl = replaced.bookUrl ?: bookUrls[i]
            val newTocUrl = replaced.tocUrl ?: tocUrls[i]
            bookUpdates.add(BookUrlUpdate(bookUrls[i], newBookUrl, newTocUrl))
            if (newBookUrl != bookUrls[i]) bookMoves.add(BookUrlMove(bookUrls[i], newBookUrl))
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
            is DomainReplaceStatement.ChapterUrlDedupe -> conn.exec(
                DomainReplaceSql.DELETE_DUPLICATE_CHAPTER_URLS,
                mapOf("prefix" to statement.prefix, "newPrefix" to statement.newPrefix)
            )

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

    // ------------------------------------------------------------------ 1. 无冲突

    @Test
    fun `无冲突时全部替换且去重语句零行`() {
        // 只保留目标空闲的书A；书B/书X 的存在不影响本章（它们由混合场景覆盖）
        conn.exec("delete from books where bookUrl in ('https://old.com/book/2', 'https://new.com/book/2')")
        val counts = executePlan(buildPlan())
        assertEquals(1, counts.books)
        assertEquals(2, counts.chapterUrl)
        assertEquals(2, counts.chapterBaseUrl)
        assertEquals(0, counts.chapterUrlDuplicates)
        assertEquals("https://new.com/book/1", single("select bookUrl from books where name = '书A'"))
        assertEquals(
            listOf("https://new.com/ch/a0.html", "https://new.com/ch/a1.html"),
            conn.strings("select url from chapters where bookUrl = 'https://new.com/book/1' order by `index`")
        )
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 2/7. 混合：干净完成、冲突保留、不同书不删

    @Test
    fun `混合场景_干净记录完成_冲突书籍保留_不同书不删`() {
        // 与 ViewModel 分区一致：书B 的目标被占用 → 书B 不进 bookUpdates，章节地址照常改写
        val counts = executePlan(buildPlan(excludeBookUrls = setOf("https://old.com/book/2")))

        assertEquals(1, counts.books)
        // 书A 完成替换
        assertEquals("https://new.com/book/1", single("select bookUrl from books where name = '书A'"))
        // 书B 原样保留（bookUrl/tocUrl 不变），同名不同书的书X也不受影响
        assertEquals("https://old.com/book/2", single("select bookUrl from books where name = '书B'"))
        assertEquals("https://new.com/book/2", single("select bookUrl from books where name = '书X'"))
        assertEquals(3, conn.scalar("select count(*) from books"))
        // 书B 的章节地址仍被改写（混态允许，后续清理旧记录或重新检查可继续）
        assertEquals(
            listOf("https://new.com/ch/b0.html", "https://new.com/ch/b1.html"),
            conn.strings("select url from chapters where bookUrl = 'https://old.com/book/2' order by `index`")
        )
        // 书X 的章节不受影响
        assertEquals(
            listOf("https://new.com/ch/x0.html"),
            conn.strings("select url from chapters where bookUrl = 'https://new.com/book/2'")
        )
    }

    // ------------------------------------------------------------------ 3/4. 章节去重与进度修正

    @Test
    fun `章节主键冲突自动去重并保持其余更新`() {
        // 书A 里同一章节存在新旧域名两行（部分迁移状态）
        conn.insertChapter(
            mapOf(
                "url" to "https://new.com/ch/a0.html",
                "title" to "重复章",
                "isVolume" to "0",
                "baseUrl" to "https://new.com",
                "bookUrl" to "https://old.com/book/1",
                "index" to "5",
                "isVip" to "0",
                "isPay" to "0",
            )
        )
        val beforeUrlCount = conn.scalar("select count(*) from chapters")
        val counts = executePlan(buildPlan(excludeBookUrls = setOf("https://old.com/book/2")))

        assertEquals(1, counts.chapterUrlDuplicates)
        // 书A 幸存的 2 行 + 书B 的 2 行都被改写（书B 走全局章节改写，与 ViewModel 行为一致）
        assertEquals(4, counts.chapterUrl)
        // 计划口径（改写 + 去重）与实际一致
        assertEquals(5, counts.chapterUrl + counts.chapterUrlDuplicates)
        // 重复行被删除：总数 = 之前 - 1
        assertEquals(beforeUrlCount - 1, conn.scalar("select count(*) from chapters"))
        assertEquals(
            listOf("https://new.com/ch/a0.html", "https://new.com/ch/a1.html"),
            conn.strings("select url from chapters where bookUrl = 'https://new.com/book/1' order by `index`")
        )
        // 保留 index 较小的旧行（目录顺序连续），删除 index 较大的新域名重复行
        assertEquals(
            "第0章",
            single("select title from chapters where url = 'https://new.com/ch/a0.html' and bookUrl = 'https://new.com/book/1'")
        )
        assertEquals(
            0,
            conn.scalar("select count(*) from chapters where title = '重复章'")
        )
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    @Test
    fun `去重后阅读位置修正与真实章节序列一致`() {
        // 书A 4 章，其中 2 章有新域名重复行（index 5、6 → 改写后撞键被删）
        conn.insertChapter(
            mapOf(
                "url" to "https://old.com/ch/a2.html", "title" to "第2章", "isVolume" to "0",
                "baseUrl" to "https://old.com", "bookUrl" to "https://old.com/book/1",
                "index" to "2", "isVip" to "0", "isPay" to "0",
            )
        )
        conn.insertChapter(
            mapOf(
                "url" to "https://old.com/ch/a3.html", "title" to "第3章", "isVolume" to "0",
                "baseUrl" to "https://old.com", "bookUrl" to "https://old.com/book/1",
                "index" to "3", "isVip" to "0", "isPay" to "0",
            )
        )
        conn.insertChapter(
            mapOf(
                "url" to "https://new.com/ch/a2.html", "title" to "重复章2", "isVolume" to "0",
                "baseUrl" to "https://new.com", "bookUrl" to "https://old.com/book/1",
                "index" to "5", "isVip" to "0", "isPay" to "0",
            )
        )
        conn.insertChapter(
            mapOf(
                "url" to "https://new.com/ch/a3.html", "title" to "重复章3", "isVolume" to "0",
                "baseUrl" to "https://new.com", "bookUrl" to "https://old.com/book/1",
                "index" to "6", "isVip" to "0", "isPay" to "0",
            )
        )
        // 阅读位置指向第 4 行（index=3，将被删除的 a3 旧行）
        conn.exec("update books set durChapterIndex = 3 where bookUrl = 'https://old.com/book/1'")

        conn.autoCommit = false
        try {
            // 与 ViewModel 相同的顺序：先按删除前快照计算修正并写回书籍，再执行去重与改写
            val ordered = conn.strings(
                "select url from chapters where bookUrl = 'https://old.com/book/1' order by `index`"
            )
            val deleted = conn.strings(
                DomainReplaceSql.DUPLICATE_CHAPTER_URLS_FOR_BOOK,
                mapOf(
                    "bookUrl" to "https://old.com/book/1",
                    "prefix" to "https://old.com",
                    "newPrefix" to "https://new.com",
                )
            ).toSet()
            assertTrue(deleted.isNotEmpty())
            val adjusted = DomainReplaceConflicts.adjustProgressAfterDedupe(3, ordered, deleted)
            // 修正指向的章节标题也取自删除前快照（与 ViewModel 一致）
            val newTitle = adjusted?.second?.let { url ->
                conn.strings("select title from chapters where url = '$url'").singleOrNull()
            }
            conn.exec(
                "update books set durChapterIndex = :idx, durChapterTitle = :title " +
                        "where bookUrl = :url",
                mapOf(
                    "idx" to adjusted?.first?.toString().orEmpty(),
                    "title" to newTitle.orEmpty(),
                    "url" to "https://old.com/book/1",
                )
            )
            val urlChanges =
                DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_URL_GROUPS), rule)
            urlChanges.forEach { change ->
                conn.exec(
                    DomainReplaceSql.DELETE_DUPLICATE_CHAPTER_URLS,
                    mapOf("prefix" to change.prefix, "newPrefix" to change.newPrefix)
                )
                conn.exec(
                    DomainReplaceSql.REPLACE_CHAPTER_URL_DOMAIN,
                    mapOf("prefix" to change.prefix, "newPrefix" to change.newPrefix)
                )
            }
            conn.commit()

            // 幸存序列：a0、a1（旧域名改写）+ a2、a3（保留的新域名行）
            val finalUrls = conn.strings(
                "select url from chapters where bookUrl = 'https://old.com/book/1' and url like 'https://new.com/%' order by `index`"
            )
            assertEquals(
                listOf(
                    "https://new.com/ch/a0.html",
                    "https://new.com/ch/a1.html",
                    "https://new.com/ch/a2.html",
                    "https://new.com/ch/a3.html",
                ),
                finalUrls
            )
            // 位置 3 指向的章节路径 /ch/a3.html 在幸存序列中仍是第 4 行
            assertEquals(3, conn.scalar("select durChapterIndex from books where bookUrl = 'https://old.com/book/1'"))
            assertEquals("第3章", single("select durChapterTitle from books where bookUrl = 'https://old.com/book/1'"))
        } finally {
            conn.autoCommit = true
        }
    }

    // ------------------------------------------------------------------ 5. A1 清理

    @Test
    fun `A1清理_级联删章节_进度分组书签统计迁移_目标章节不动`() {
        // 目标记录：新域名、无进度、默认分组，8 章。
        // 注意：与旧记录同名同作者的两行被 UNIQUE(name,author) 禁止，
        // 能共存的旧/新记录必然 name/author 有差异——目标用「书A新版」。
        insertBook("https://new.com/book/1", "https://new.com/book/1/toc", "书A新版", "作者A")
        insertChapters(
            "https://new.com/book/1",
            (0 until 8).map { "https://new.com/book/1/c$it.html" },
            "https://new.com",
        )
        // 旧记录的阅读进度指向第 4 章（路径 c3.html），分组 2，有书签与阅读统计
        conn.exec("update books set durChapterIndex = 3, durChapterPos = 512, durChapterTime = 1000, `group` = 2 where bookUrl = 'https://old.com/book/1'")
        conn.insertChapter(
            mapOf(
                "url" to "https://old.com/book/1/c3.html", "title" to "第4卷 第3章", "isVolume" to "0",
                "baseUrl" to "https://old.com", "bookUrl" to "https://old.com/book/1",
                "index" to "3", "isVip" to "0", "isPay" to "0",
            )
        )
        conn.insertBookmarkedRow("书A", "作者A", 3, "https://old.com/book/1/c3.html")
        conn.insertReadRecordRow("书A", 12345)

        conn.autoCommit = false
        try {
            // 与 ViewModel 清理流程相同的顺序：迁移保留记录 → 关联数据改挂 → 删除旧记录
            conn.exec(
                "update books set durChapterIndex = 3, durChapterPos = 512, durChapterTime = 1000, " +
                        "`group` = `group` | 2 where bookUrl = 'https://new.com/book/1'"
            )
            conn.exec(
                DomainReplaceSql.REKEY_BOOKMARKS, mapOf(
                    "oldName" to "书A", "oldAuthor" to "作者A",
                    "newName" to "书A新版", "newAuthor" to "作者A",
                )
            )
            conn.exec(
                DomainReplaceSql.REKEY_READ_RECORD, mapOf("oldName" to "书A", "newName" to "书A新版")
            )
            conn.exec("delete from books where bookUrl = 'https://old.com/book/1'")
            conn.commit()
        } finally {
            conn.autoCommit = true
        }

        // 旧记录及其章节（含进度指向章）已级联删除
        assertEquals(0, conn.scalar("select count(*) from books where bookUrl = 'https://old.com/book/1'"))
        assertEquals(
            0,
            conn.scalar("select count(*) from chapters where bookUrl = 'https://old.com/book/1'")
        )
        // 保留记录：进度迁移、分组合并、自身 8 章原样
        val kept = conn.strings(
            "select durChapterIndex||'|'||durChapterPos||'|'||durChapterTime||'|'||`group` " +
                    "from books where bookUrl = 'https://new.com/book/1'"
        ).single()
        assertEquals("3|512|1000|2", kept)
        assertEquals(
            8,
            conn.scalar("select count(*) from chapters where bookUrl = 'https://new.com/book/1'")
        )
        // 书签与阅读统计已挂到保留记录名下
        assertEquals(
            1,
            conn.scalar("select count(*) from bookmarks where bookName = '书A新版' and bookAuthor = '作者A'")
        )
        assertEquals(
            12345,
            conn.scalar("select readTime from readRecord where bookName = '书A新版'")
        )
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 6. 本地书籍保护

    @Test
    fun `本地书籍不被域名替换命中且同名约束维持全局唯一`() {
        // (name, author) 唯一索引是全局约束：同名同作者的两行不可能共存（结构性事实）
        assertThrows(SQLException::class.java) {
            conn.insertBook(
                mapOf(
                    "bookUrl" to "https://other.com/book/9",
                    "tocUrl" to "https://other.com/book/9/toc",
                    "name" to "书A",
                    "author" to "作者A",
                )
            )
        }
        // 本地书籍：origin = loc_book、地址非 http(s)
        conn.insertBook(
            mapOf(
                "bookUrl" to "/storage/emulated/0/Books/local.txt",
                "tocUrl" to "/storage/emulated/0/Books/local.txt",
                "name" to "本地书L",
                "author" to "作者L",
                "origin" to "loc_book",
            )
        )
        // 与 ViewModel 分区一致：书B 的目标被占用 → 不进 bookUpdates，其余照常执行
        executePlan(buildPlan(excludeBookUrls = setOf("https://old.com/book/2")))
        // 本地书逐字不动
        val local = conn.strings(
            "select bookUrl||'|'||tocUrl||'|'||origin from books where name = '本地书L'"
        ).single()
        assertEquals("/storage/emulated/0/Books/local.txt|/storage/emulated/0/Books/local.txt|loc_book", local)
        assertEquals(
            0,
            conn.scalar("select count(*) from chapters where bookUrl = '/storage/emulated/0/Books/local.txt'")
        )
    }

    // ------------------------------------------------------------------ 8. 单条失败不回滚已提交结果

    @Test
    fun `单条处理失败时已提交的其他结果不受影响`() {
        val counts = executePlan(buildPlan(excludeBookUrls = setOf("https://old.com/book/2")))
        assertEquals(1, counts.books)

        // 随后的单本替换撞已占用主键：只回滚这一条事务
        conn.autoCommit = false
        val error = assertThrows(SQLException::class.java) {
            conn.exec(
                DomainReplaceSql.UPDATE_BOOK_URLS,
                mapOf(
                    "oldBookUrl" to "https://old.com/book/2",
                    "newBookUrl" to "https://new.com/book/2",
                    "newTocUrl" to "https://new.com/book/2/toc",
                )
            )
            conn.commit()
        }
        assertTrue("实际错误：${error.message}", error.message!!.contains("UNIQUE", true))
        runCatching { conn.rollback() }
        conn.autoCommit = true

        // 已提交的替换完好；失败的那本保持原样
        assertEquals("https://new.com/book/1", single("select bookUrl from books where name = '书A'"))
        assertEquals("https://old.com/book/2", single("select bookUrl from books where name = '书B'"))
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 9. 冲突消失后单本完成替换

    @Test
    fun `冲突消失后按单书作用域完成替换`() {
        // 目标书被用户删除（冲突消失）
        conn.exec("delete from books where bookUrl = 'https://new.com/book/2'")
        // 与 ViewModel.replaceSingleBook 相同的作用域 SQL 顺序
        conn.autoCommit = false
        try {
            val urlChanges =
                DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_URL_GROUPS), rule)
            urlChanges.forEach { change ->
                conn.exec(
                    DomainReplaceSql.DELETE_DUPLICATE_CHAPTER_URLS_FOR_BOOK,
                    mapOf(
                        "bookUrl" to "https://old.com/book/2",
                        "prefix" to change.prefix,
                        "newPrefix" to change.newPrefix,
                    )
                )
                conn.exec(
                    DomainReplaceSql.REPLACE_CHAPTER_URL_DOMAIN_FOR_BOOK,
                    mapOf(
                        "bookUrl" to "https://old.com/book/2",
                        "prefix" to change.prefix,
                        "newPrefix" to change.newPrefix,
                    )
                )
                conn.exec(
                    DomainReplaceSql.REPLACE_CHAPTER_BASE_URL_DOMAIN_FOR_BOOK,
                    mapOf(
                        "bookUrl" to "https://old.com/book/2",
                        "prefix" to change.prefix,
                        "newPrefix" to change.newPrefix,
                    )
                )
                conn.exec(
                    DomainReplaceSql.REPLACE_CHAPTER_RESOURCE_URL_DOMAIN_FOR_BOOK,
                    mapOf(
                        "bookUrl" to "https://old.com/book/2",
                        "prefix" to change.prefix,
                        "newPrefix" to change.newPrefix,
                    )
                )
            }
            conn.execRaw(DomainReplaceExecutor.DEFER_FOREIGN_KEYS)
            conn.exec(
                DomainReplaceSql.UPDATE_BOOK_URLS,
                mapOf(
                    "oldBookUrl" to "https://old.com/book/2",
                    "newBookUrl" to "https://new.com/book/2",
                    "newTocUrl" to "https://new.com/book/2/toc",
                )
            )
            conn.exec(
                DomainReplaceSql.REPLACE_CHAPTER_BOOK_URL,
                mapOf("oldBookUrl" to "https://old.com/book/2", "newBookUrl" to "https://new.com/book/2")
            )
            conn.commit()
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }

        assertEquals("https://new.com/book/2", single("select bookUrl from books where name = '书B'"))
        assertEquals(
            listOf("https://new.com/ch/b0.html", "https://new.com/ch/b1.html"),
            conn.strings("select url from chapters where bookUrl = 'https://new.com/book/2' order by `index`")
        )
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 10. 幂等与统计一致

    @Test
    fun `执行后重查为零且统计与库内实际一致`() {
        // 只保留书A：其余由混合/清理场景覆盖
        conn.exec("delete from books where bookUrl in ('https://old.com/book/2', 'https://new.com/book/2')")
        val counts = executePlan(buildPlan())
        // 重查：与 ViewModel.countRemainingHits 同口径
        val bookHits = conn.strings("select bookUrl from books")
            .count { UrlDomainReplacement.replaceDomain(it, rule) != null } +
                conn.strings("select tocUrl from books")
                    .count { UrlDomainReplacement.replaceDomain(it, rule) != null }
        val chapterHits = listOf(
            DomainReplaceSql.CHAPTER_URL_GROUPS,
            DomainReplaceSql.CHAPTER_BASE_URL_GROUPS,
            DomainReplaceSql.CHAPTER_RESOURCE_URL_GROUPS,
        ).sumOf { sql ->
            DomainReplaceExecutor.changes(conn.groups(sql), rule).sumOf { it.count }
        }
        assertEquals(0, bookHits)
        assertEquals(0, chapterHits)
        // SQL 返回行数与库内实际状态一致
        assertEquals(2, conn.scalar("select count(*) from chapters where url like 'https://new.com/%'"))
        assertEquals(2, counts.chapterUrl)
        assertEquals(1, conn.scalar("select count(*) from books where bookUrl like 'https://new.com/%'"))
        assertEquals(1, counts.books)
    }

    private fun single(sql: String): String = conn.strings(sql).single()

    // ------------------------------------------------------------------ bookmarks/readRecord 种子

    private fun Connection.insertBookmarkedRow(
        name: String,
        author: String,
        chapterIndex: Int,
        chapterName: String,
    ) {
        prepareStatement(
            "insert into bookmarks (time, bookName, bookAuthor, chapterIndex, chapterPos, chapterName, bookText, content) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setLong(1, 1000L)
            statement.setString(2, name)
            statement.setString(3, author)
            statement.setInt(4, chapterIndex)
            statement.setInt(5, 100)
            statement.setString(6, chapterName)
            statement.setString(7, "选中文本")
            statement.setString(8, "想法内容")
            statement.executeUpdate()
        }
    }

    private fun Connection.insertReadRecordRow(name: String, readTime: Long) {
        prepareStatement(
            "insert into readRecord (deviceId, bookName, readTime, lastRead) values (?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, "test-device")
            statement.setString(2, name)
            statement.setLong(3, readTime)
            statement.setLong(4, 2000L)
            statement.executeUpdate()
        }
    }
}
