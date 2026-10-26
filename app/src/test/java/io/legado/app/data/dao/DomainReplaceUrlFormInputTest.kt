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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * 真机回归（2026-10）：输入完整网址形式的旧域名 `https://www.banzhu\d*.net` 时，
 * 预览提示「数据库中没有匹配的记录」，而库中明明存在
 * `www.banzhu222222222.net` / `www.banzhu111111111.net` 等主机。
 *
 * 根因：正则只作用于解析出的纯主机名（整串匹配），模式里的 `https://` 前缀
 * 永远整串失配；修复后 normalize 先提取网址的主机名部分再分类。
 *
 * 本类用与 ViewModel 预览 / 执行**完全相同**的数据链路（生产分组 SQL +
 * [DomainReplaceExecutor]）复现真机数据形态，断言：
 * 1. 该写法在预览阶段命中非零（走 Ready 分支，预览弹窗会出现「执行替换」）；
 * 2. 执行提交后书籍 / 目录 / 章节三处地址一致，无关站点不动，端口原样保留；
 * 3. 提交后用同一规则重查剩余命中为 0（countRemainingHits 的口径）。
 */
class DomainReplaceUrlFormInputTest {

    private lateinit var conn: Connection

    @Before
    fun setUp() {
        conn = RealSqliteSupport.open()
        // 书 A：真机出现的 banzhu222222222 主机，两章；其中一章 baseUrl 带端口
        conn.insertBook(
            mapOf(
                "bookUrl" to "https://www.banzhu222222222.net/book/1",
                "tocUrl" to "https://www.banzhu222222222.net/book/1/toc",
                "name" to "书A",
                "author" to "作者A",
            )
        )
        conn.insertChapter(
            mapOf(
                "url" to "https://www.banzhu222222222.net/ch/0.html",
                "title" to "第0章",
                "isVolume" to "0",
                "baseUrl" to "https://www.banzhu222222222.net",
                "bookUrl" to "https://www.banzhu222222222.net/book/1",
                "index" to "0",
                "isVip" to "0",
                "isPay" to "0",
            )
        )
        conn.insertChapter(
            mapOf(
                "url" to "https://www.banzhu222222222.net/ch/1.html",
                "title" to "第1章",
                "isVolume" to "0",
                "baseUrl" to "https://www.banzhu222222222.net:8080",
                "bookUrl" to "https://www.banzhu222222222.net/book/1",
                "index" to "1",
                "isVip" to "0",
                "isPay" to "0",
            )
        )
        // 书 B：另一个 banzhu 数字主机
        conn.insertBook(
            mapOf(
                "bookUrl" to "https://www.banzhu111111111.net/book/2",
                "tocUrl" to "https://www.banzhu111111111.net/book/2/toc",
                "name" to "书B",
                "author" to "作者B",
            )
        )
        conn.insertChapter(
            mapOf(
                "url" to "https://www.banzhu111111111.net/ch/0.html",
                "title" to "第0章",
                "isVolume" to "0",
                "baseUrl" to "https://www.banzhu111111111.net",
                "bookUrl" to "https://www.banzhu111111111.net/book/2",
                "index" to "0",
                "isVip" to "0",
                "isPay" to "0",
            )
        )
        // 书 C：无关站点，必须原样保留
        conn.insertBook(
            mapOf(
                "bookUrl" to "https://www.other-site.net/book/3",
                "tocUrl" to "https://www.other-site.net/book/3/toc",
                "name" to "无关书",
                "author" to "作者C",
            )
        )
        conn.insertChapter(
            mapOf(
                "url" to "https://www.other-site.net/ch/0.html",
                "title" to "第0章",
                "isVolume" to "0",
                "baseUrl" to "https://www.other-site.net",
                "bookUrl" to "https://www.other-site.net/book/3",
                "index" to "0",
                "isVip" to "0",
                "isPay" to "0",
            )
        )
    }

    @After
    fun tearDown() {
        conn.close()
    }

    private fun banzhuRule(): UrlDomainReplacement.DomainRule {
        return when (val r = UrlDomainReplacement.normalize("https://www.banzhu\\d*.net", "www.newsite.com")) {
            is UrlDomainReplacement.NormalizeResult.Success -> r.rule
            is UrlDomainReplacement.NormalizeResult.Failure ->
                throw AssertionError("规则应合法，实际失败：${r.reason}")
        }
    }

    @Test
    fun `真机用例_完整网址正则在预览阶段命中非零`() {
        val r = banzhuRule()
        assertEquals("www.banzhu\\d*.net", r.pattern)

        val urlChanges =
            DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_URL_GROUPS), r)
        val baseUrlChanges =
            DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_BASE_URL_GROUPS), r)
        val resourceUrlChanges = DomainReplaceExecutor.changes(
            conn.groups(DomainReplaceSql.CHAPTER_RESOURCE_URL_GROUPS),
            r
        )

        // 章节 url：书A 2 章 + 书B 1 章 = 3；baseUrl 同为 3（含带端口的一条）
        assertEquals(3, urlChanges.sumOf { it.count })
        assertEquals(3, baseUrlChanges.sumOf { it.count })
        assertEquals(0, resourceUrlChanges.sumOf { it.count })

        // 命中的主机只有 banzhu 系列，无关站点不在其列
        val hosts = (urlChanges + baseUrlChanges).map { it.authority }
        assertTrue(hosts.none { it.contains("other-site") })
        assertTrue(hosts.any { it.contains("banzhu222222222.net") })
        assertTrue(hosts.any { it.contains("banzhu111111111.net") })

        // 书籍两处地址：书A、书B 命中，无关书不命中
        val bookUrls = conn.strings("select bookUrl from books order by bookUrl")
        val tocUrls = conn.strings("select tocUrl from books order by bookUrl")
        val bookHits = bookUrls.indices.count { i ->
            UrlDomainReplacement.replaceBookUrls(bookUrls[i], tocUrls[i], r) != null
        }
        assertEquals(2, bookHits)
    }

    @Test
    fun `真机用例_执行提交后三处地址一致且剩余命中为零`() {
        val counts = executePlan(buildPlan())

        assertEquals(3, counts.chapterUrl)
        assertEquals(3, counts.chapterBaseUrl)
        assertEquals(2, counts.books)
        assertEquals(3, counts.chapterBookUrl)

        // 书A：url 与 bookUrl 归属都换到新域名
        assertEquals(
            listOf(
                "https://www.newsite.com/ch/0.html",
                "https://www.newsite.com/ch/1.html",
            ),
            conn.strings(
                "select url from chapters where bookUrl = 'https://www.newsite.com/book/1' order by `index`"
            )
        )
        assertEquals(
            "https://www.newsite.com/book/1|https://www.newsite.com/book/1/toc",
            conn.strings(
                "select bookUrl||'|'||tocUrl from books where name = '书A'"
            ).single()
        )
        // 书A 的 baseUrl：一条无端口、一条端口原样保留
        assertEquals(
            listOf("https://www.newsite.com", "https://www.newsite.com:8080"),
            conn.strings(
                "select baseUrl from chapters where bookUrl = 'https://www.newsite.com/book/1' order by `index`"
            )
        )
        // 书B 同步换域名
        assertEquals(
            "https://www.newsite.com/book/2|https://www.newsite.com/book/2/toc",
            conn.strings("select bookUrl||'|'||tocUrl from books where name = '书B'").single()
        )
        // 无关站点逐字不动
        assertEquals(
            "https://www.other-site.net/book/3|https://www.other-site.net/book/3/toc",
            conn.strings("select bookUrl||'|'||tocUrl from books where name = '无关书'").single()
        )
        assertEquals(
            listOf("https://www.other-site.net/ch/0.html"),
            conn.strings("select url from chapters where bookUrl = 'https://www.other-site.net/book/3'")
        )

        // 提交后用同一规则重查：剩余命中必须为 0（countRemainingHits 同口径）
        assertEquals(0, remainingHits(banzhuRule()))
        assertEquals(emptyList<String>(), conn.foreignKeyViolations())
    }

    // ------------------------------------------------------------------ 内部

    private fun buildPlan(): DomainReplacePlan {
        val rule = banzhuRule()
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
                UrlDomainReplacement.replaceBookUrls(bookUrls[i], tocUrls[i], rule) ?: return@forEach
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

    /** 与 ViewModel.countRemainingHits 同口径：提交后用同一规则重查全部字段 */
    private fun remainingHits(rule: UrlDomainReplacement.DomainRule): Int {
        val chapterHits = listOf(
            DomainReplaceSql.CHAPTER_URL_GROUPS,
            DomainReplaceSql.CHAPTER_BASE_URL_GROUPS,
            DomainReplaceSql.CHAPTER_RESOURCE_URL_GROUPS,
        ).sumOf { sql ->
            DomainReplaceExecutor.changes(conn.groups(sql), rule).sumOf { it.count }
        }
        val bookHits = conn.strings("select bookUrl from books")
            .count { UrlDomainReplacement.replaceDomain(it, rule) != null } +
                conn.strings("select tocUrl from books")
                    .count { UrlDomainReplacement.replaceDomain(it, rule) != null }
        return chapterHits + bookHits
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
