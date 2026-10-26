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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * 「批量替换域名」的**真实 SQL 回归测试**。
 *
 * 关键点：这里执行的 SQL 是 [DomainReplaceSql] 里的常量，与 `BookChapterDao` / `BookDao`
 * 的 `@Query` 注解引用的是**同一个字符串**；建表语句取自 Room 导出的生产 schema。
 * 因此测的是生产 SQL，而不是复制一份或另写一个 Kotlin 字符串函数。
 *
 * 无法覆盖的部分（明确声明）：Room 生成的 DAO 实现依赖 `android.database.sqlite`，
 * JVM 上跑不了。本测试用 JDBC 驱动同一个 SQLite 引擎执行同一段 SQL，
 * 具名参数按 Room 的方式（出现顺序 → `?`）绑定。Room 的编译期 SQL 校验、
 * Android SQLite 驱动层的行为不在此测试范围内。
 */
class DomainReplaceSqlTest {

    private lateinit var conn: Connection

    /** 一个正则同时命中两个历史域名：带端口的 `m.old.com` 与不带端口的 `www.old.com` */
    private val rule = ruleOf("^(m|www)\\.old\\.com$", "new.com")

    private val b1Old = "http://m.old.com:8080/book/1"
    private val b2Old = "https://www.old.com/book/2"

    @Before
    fun setUp() {
        conn = RealSqliteSupport.open()
        seed()
    }

    @After
    fun tearDown() {
        conn.close()
    }

    // ------------------------------------------------------------------ 造数据

    private fun seed() {
        conn.insertBook(
            mapOf(
                "bookUrl" to b1Old,
                "tocUrl" to "$b1Old/toc",
                "name" to "旧域名书",
                "author" to "作者甲",
                "origin" to "https://source.one",
                "originName" to "源一",
                "durChapterIndex" to "12",
                "durChapterPos" to "345",
                "durChapterTitle" to "第十二章",
                "group" to "3",
                "totalChapterNum" to "9",
                "order" to "5",
                "originOrder" to "7",
                "readConfig" to "{\"font\":1}",
            )
        )
        conn.insertBook(
            mapOf(
                "bookUrl" to b2Old,
                "tocUrl" to "$b2Old/toc",
                "name" to "另一本",
                "author" to "作者乙",
            )
        )
        conn.insertBook(
            mapOf(
                "bookUrl" to "http://other.com/book/3",
                "tocUrl" to "http://other.com/book/3/toc",
                "name" to "无关书",
                "author" to "作者丙",
            )
        )

        // 章节：覆盖 5 种 authority 终止符（串尾 / `/` / `?` / `#` / `,{`）、
        // 边界域名、第三方 CDN、以及 baseUrl / resourceUrl 两个独立列
        val chapters = listOf(
            // index, url, baseUrl, resourceUrl, tag
            Triple(
                "http://m.old.com:8080/ch/0.html",
                "http://m.old.com:8080",
                "http://m.old.com:8080/r/0"
            ) to "t0",
            Triple(
                "http://m.old.com:8080/ch/1.html?a=1&b=2#frag",
                "http://m.old.com:8080",
                null
            ) to "t1",
            Triple("http://m.old.com:8080", "http://m.old.com:8080", null) to "t2",
            Triple(
                "http://m.old.com:8080/ch/3.html,{\"method\":\"POST\"}",
                "http://m.old.com:8080",
                null
            ) to "t3",
            // 边界：不是同一个 authority，必须保持原样
            Triple(
                "http://m.old.com.evil.com/ch/4.html",
                "http://m.old.com.evil.com",
                "https://cdn.partner.com/img/6.jpg"
            ) to "t4",
            Triple("http://xm.old.com/ch/5.html", "http://xm.old.com", null) to "t5",
            // 规则参数块紧跟域名
            Triple(
                "http://m.old.com:8080,{\"method\":\"POST\"}",
                "http://m.old.com:8080",
                null
            ) to "t6",
            Triple("http://m.old.com:8080?a=1", "http://m.old.com:8080", null) to "t7",
            Triple("http://m.old.com:8080#frag", "http://m.old.com:8080", null) to "t8",
        )
        chapters.forEachIndexed { index, (fields, tag) ->
            val (url, baseUrl, resourceUrl) = fields
            val values = mutableMapOf(
                "url" to url,
                "title" to "第${index}章",
                "isVolume" to "0",
                "baseUrl" to baseUrl,
                "bookUrl" to b1Old,
                "index" to index.toString(),
                "isVip" to "0",
                "isPay" to "0",
                "tag" to tag,
            )
            if (resourceUrl != null) values["resourceUrl"] = resourceUrl
            conn.insertChapter(values)
        }

        listOf("https://www.old.com/ch/0.html", "https://www.old.com/ch/1.html")
            .forEachIndexed { index, url ->
                conn.insertChapter(
                    mapOf(
                        "url" to url,
                        "title" to "B2-$index",
                        "isVolume" to "0",
                        "baseUrl" to "https://www.old.com",
                        "bookUrl" to b2Old,
                        "index" to index.toString(),
                        "isVip" to "0",
                        "isPay" to "0",
                    )
                )
            }

        conn.insertChapter(
            mapOf(
                "url" to "http://other.com/ch/0.html",
                "title" to "B3-0",
                "isVolume" to "0",
                "baseUrl" to "http://other.com",
                "bookUrl" to "http://other.com/book/3",
                "index" to "0",
                "isVip" to "0",
                "isPay" to "0",
            )
        )
    }

    // ------------------------------------------------------------------ 1. 分组查询

    @Test
    fun `分组查询按 scheme 与 authority 正确聚合`() {
        val actual = conn.groups(DomainReplaceSql.CHAPTER_URL_GROUPS)
            .map { Triple(it.scheme, it.authority, it.cnt) }
            .sortedWith(compareBy({ it.first }, { it.second }))

        assertEquals(
            listOf(
                Triple("http://", "m.old.com.evil.com", 1),
                Triple("http://", "m.old.com:8080", 7),
                Triple("http://", "other.com", 1),
                Triple("http://", "xm.old.com", 1),
                Triple("https://", "www.old.com", 2),
            ),
            actual
        )
    }

    @Test
    fun `baseUrl 与 resourceUrl 的分组查询各自独立`() {
        val base = conn.groups(DomainReplaceSql.CHAPTER_BASE_URL_GROUPS)
            .associate { it.scheme + it.authority to it.cnt }
        assertEquals(7, base["http://m.old.com:8080"])
        assertEquals(1, base["http://m.old.com.evil.com"])
        assertEquals(1, base["http://xm.old.com"])
        assertEquals(2, base["https://www.old.com"])

        val resource = conn.groups(DomainReplaceSql.CHAPTER_RESOURCE_URL_GROUPS)
            .associate { it.scheme + it.authority to it.cnt }
        assertEquals(1, resource["http://m.old.com:8080"])
        // 第三方 CDN 必须出现在分组里（预览能看到它），但不会被规则命中
        assertEquals(1, resource["https://cdn.partner.com"])
    }

    // ------------------------------------------------------------------ 2. 执行与断言

    @Test
    fun `整库替换后章节地址逐条正确`() {
        val counts = execute()
        assertEquals(9, counts.chapterUrl)
        assertEquals(9, counts.chapterBaseUrl)
        assertEquals(1, counts.chapterResourceUrl)
        assertEquals(2, counts.books)
        assertEquals(11, counts.chapterBookUrl)

        val b1 = conn.strings(
            "select url from chapters where bookUrl = :bookUrl order by `index`",
            mapOf("bookUrl" to "http://new.com:8080/book/1")
        )
        assertEquals(
            listOf(
                "http://new.com:8080/ch/0.html",
                "http://new.com:8080/ch/1.html?a=1&b=2#frag",
                "http://new.com:8080",
                "http://new.com:8080/ch/3.html,{\"method\":\"POST\"}",
                "http://m.old.com.evil.com/ch/4.html",
                "http://xm.old.com/ch/5.html",
                "http://new.com:8080,{\"method\":\"POST\"}",
                "http://new.com:8080?a=1",
                "http://new.com:8080#frag",
            ),
            b1
        )

        val b2 = conn.strings(
            "select url from chapters where bookUrl = :bookUrl order by `index`",
            mapOf("bookUrl" to "https://new.com/book/2")
        )
        assertEquals(
            listOf("https://new.com/ch/0.html", "https://new.com/ch/1.html"),
            b2
        )

        val b3 = conn.strings(
            "select url from chapters where bookUrl = :bookUrl order by `index`",
            mapOf("bookUrl" to "http://other.com/book/3")
        )
        assertEquals(listOf("http://other.com/ch/0.html"), b3)
    }

    @Test
    fun `带端口的旧域名不会拼出坏 URL`() {
        // 历史 bug：SET url = substr(url,1,length(:prefix)) || :newAuthority || substr(...)
        // 会把旧 authority 留在原地，得到 http://m.old.com:8080m.new.com:8080/ch/0.html
        execute()
        val all = conn.strings("select url from chapters")
        assertFalse(
            "出现旧 authority 残留：$all",
            all.any { Regex("old\\.com:\\d+new\\.com").containsMatchIn(it) }
        )
        assertFalse(
            "出现端口被吞掉：$all",
            all.any { it.contains("old.com:8080m.") || it.contains("8080new.com") }
        )
        // 端口必须原样保留
        assertTrue(all.contains("http://new.com:8080/ch/0.html"))
    }

    @Test
    fun `协议与端口在替换后保持不变`() {
        execute()
        val http = conn.strings(
            "select url from chapters where bookUrl = :b order by `index`",
            mapOf("b" to "http://new.com:8080/book/1")
        )
        // 7 条命中项全部是 http，且都带 :8080
        val matched = http.filter { !it.contains("evil.com") && !it.contains("xm.old.com") }
        assertEquals(7, matched.size)
        assertTrue(matched.all { it.startsWith("http://new.com:8080") })
        assertTrue(matched.none { it.startsWith("https://") })

        val https = conn.strings(
            "select url from chapters where bookUrl = :b",
            mapOf("b" to "https://new.com/book/2")
        )
        assertEquals(2, https.size)
        // 协议不自动升级/降级
        assertTrue(https.all { it.startsWith("https://new.com/") })
    }

    @Test
    fun `baseUrl 与 resourceUrl 各自替换且互不影响`() {
        execute()
        val base = conn.strings(
            "select baseUrl from chapters where bookUrl = :b order by `index`",
            mapOf("b" to "http://new.com:8080/book/1")
        )
        assertEquals(
            listOf(
                "http://new.com:8080",
                "http://new.com:8080",
                "http://new.com:8080",
                "http://new.com:8080",
                "http://m.old.com.evil.com",
                "http://xm.old.com",
                "http://new.com:8080",
                "http://new.com:8080",
                "http://new.com:8080",
            ),
            base
        )

        val resource = conn.strings(
            "select resourceUrl from chapters where resourceUrl is not null order by bookUrl, `index`"
        )
        assertEquals(
            listOf(
                "http://new.com:8080/r/0",
                "https://cdn.partner.com/img/6.jpg",
            ),
            resource
        )
    }

    @Test
    fun `书籍主键与目录地址同步更新`() {
        execute()
        assertEquals(
            listOf("http://new.com:8080/book/1|http://new.com:8080/book/1/toc"),
            conn.strings(
                "select bookUrl||'|'||tocUrl from books where name = :n",
                mapOf("n" to "旧域名书")
            )
        )
        assertEquals(
            listOf("https://new.com/book/2|https://new.com/book/2/toc"),
            conn.strings(
                "select bookUrl||'|'||tocUrl from books where name = :n",
                mapOf("n" to "另一本")
            )
        )
        // 不相关书籍完全不动
        assertEquals(
            listOf("http://other.com/book/3|http://other.com/book/3/toc"),
            conn.strings(
                "select bookUrl||'|'||tocUrl from books where name = :n",
                mapOf("n" to "无关书")
            )
        )
    }

    @Test
    fun `章节归属跟上书籍主键且外键完整`() {
        execute()
        assertEquals(0, conn.scalar("select count(*) from chapters where bookUrl like '%old.com%'"))
        assertEquals(
            emptyList<String>(),
            conn.foreignKeyViolations()
        )
    }

    // ------------------------------------------------------------------ 3. 预览口径 == 实际写入

    @Test
    fun `预览命中数与实际影响行数完全一致`() {
        val plan = buildPlan()
        val previewUrl = plan.urlChanges.sumOf { it.count }
        val previewBase = plan.baseUrlChanges.sumOf { it.count }
        val previewResource = plan.resourceUrlChanges.sumOf { it.count }
        // 必须在执行前统计，执行后旧 bookUrl 已不存在
        val previewChapterBookUrl = plan.bookMoves.sumOf { move ->
            conn.scalar(
                "select count(*) from chapters where bookUrl = :b",
                mapOf("b" to move.oldBookUrl)
            )
        }

        val counts = execute()

        assertEquals(previewUrl, counts.chapterUrl)
        assertEquals(previewBase, counts.chapterBaseUrl)
        assertEquals(previewResource, counts.chapterResourceUrl)
        assertEquals(plan.bookUpdates.size, counts.books)
        assertEquals(previewChapterBookUrl, counts.chapterBookUrl)
    }

    // ------------------------------------------------------------------ 4. 幂等

    @Test
    fun `重复执行不再改动任何行`() {
        val first = execute()
        assertTrue(first.chapterUrl > 0)

        val secondPlan = buildPlan()
        assertTrue("第二次应当没有可替换项：$secondPlan", secondPlan.isEmpty)
        val second = DomainReplaceExecutor.run(
            plan = secondPlan,
            execSql = { conn.execRaw(it) },
            apply = { applyStatement(it) }
        )
        assertEquals(DomainReplaceCounts(), second)

        // 数据与第一次执行后完全相同
        assertEquals(
            7,
            conn.scalar("select count(*) from chapters where url like 'http://new.com:8080%'")
        )
        assertEquals(
            0,
            conn.scalar("select count(*) from chapters where url like 'http://m.old.com:8080/%'")
        )
    }

    // ------------------------------------------------------------------ 5. 主键冲突预检

    @Test
    fun `章节主键冲突能被预检查出`() {
        // 同一本书里已经存在「新域名版本」的同一章
        conn.insertChapter(
            mapOf(
                "url" to "http://new.com:8080/ch/0.html",
                "title" to "重复章",
                "isVolume" to "0",
                "baseUrl" to "http://new.com:8080",
                "bookUrl" to b1Old,
                "index" to "99",
                "isVip" to "0",
                "isPay" to "0",
            )
        )

        val plan = buildPlan()
        val change = plan.urlChanges.first { it.authority == "m.old.com:8080" }
        val conflicts = conn.scalar(
            DomainReplaceSql.COUNT_CHAPTER_URL_CONFLICTS,
            mapOf("prefix" to change.prefix, "newPrefix" to change.newPrefix)
        )
        assertEquals(1, conflicts)
    }

    @Test
    fun `无冲突时预检返回零`() {
        val plan = buildPlan()
        plan.urlChanges.forEach { change ->
            assertEquals(
                0,
                conn.scalar(
                    DomainReplaceSql.COUNT_CHAPTER_URL_CONFLICTS,
                    mapOf("prefix" to change.prefix, "newPrefix" to change.newPrefix)
                )
            )
        }
    }

    // ------------------------------------------------------------------ 6. 进度与来源不被触碰

    @Test
    fun `替换不影响阅读进度与书源绑定`() {
        val before = conn.strings(
            "select origin||'|'||originName||'|'||durChapterIndex||'|'||durChapterPos||'|'||" +
                    "durChapterTitle||'|'||`group`||'|'||totalChapterNum||'|'||`order`||'|'||" +
                    "originOrder||'|'||readConfig from books where name = :n",
            mapOf("n" to "旧域名书")
        )
        val chapterBefore = conn.strings(
            "select title||'|'||`index`||'|'||isVip||'|'||isPay||'|'||tag from chapters " +
                    "where bookUrl = :b order by `index`",
            mapOf("b" to b1Old)
        )

        execute()

        val after = conn.strings(
            "select origin||'|'||originName||'|'||durChapterIndex||'|'||durChapterPos||'|'||" +
                    "durChapterTitle||'|'||`group`||'|'||totalChapterNum||'|'||`order`||'|'||" +
                    "originOrder||'|'||readConfig from books where name = :n",
            mapOf("n" to "旧域名书")
        )
        assertEquals(before, after)
        assertEquals(
            listOf("https://source.one|源一|12|345|第十二章|3|9|5|7|{\"font\":1}"),
            after
        )

        val chapterAfter = conn.strings(
            "select title||'|'||`index`||'|'||isVip||'|'||isPay||'|'||tag from chapters " +
                    "where bookUrl = :b order by `index`",
            mapOf("b" to "http://new.com:8080/book/1")
        )
        assertEquals(chapterBefore, chapterAfter)
    }

    // ------------------------------------------------------------------ 内部

    private fun ruleOf(old: String, new: String): UrlDomainReplacement.DomainRule {
        return when (val r = UrlDomainReplacement.normalize(old, new)) {
            is UrlDomainReplacement.NormalizeResult.Success -> r.rule
            is UrlDomainReplacement.NormalizeResult.Failure -> throw AssertionError(r.reason.name)
        }
    }

    /** 与 `BookshelfManageViewModel` 里构造计划的逻辑一致（分组查询 + 生产 SQL 分组 → 待替换项） */
    private fun buildPlan(): DomainReplacePlan {
        val urlChanges =
            DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_URL_GROUPS), rule)
        val baseUrlChanges =
            DomainReplaceExecutor.changes(conn.groups(DomainReplaceSql.CHAPTER_BASE_URL_GROUPS), rule)
        val resourceUrlChanges = DomainReplaceExecutor.changes(
            conn.groups(DomainReplaceSql.CHAPTER_RESOURCE_URL_GROUPS),
            rule
        )

        val bookUrls = conn.strings("select bookUrl from books order by bookUrl")
        val tocUrls = conn.strings("select tocUrl from books order by bookUrl")
        val bookUpdates = mutableListOf<BookUrlUpdate>()
        val bookMoves = mutableListOf<BookUrlMove>()
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

    /** 一条写操作 → 生产 SQL（与 DAO 的 `@Query` 是同一个常量） */
    private fun applyStatement(statement: DomainReplaceStatement): Int {
        return when (statement) {
            is DomainReplaceStatement.ChapterDedupLift -> conn.exec(
                DomainReplaceSql.LIFT_CHAPTER_INDEXES,
                mapOf(
                    "prefix" to statement.prefix,
                    "newPrefix" to statement.newPrefix,
                    "offset" to statement.offset.toString(),
                )
            )

            is DomainReplaceStatement.ChapterUrlDedupe -> conn.exec(
                DomainReplaceSql.DELETE_DUPLICATE_CHAPTER_URLS,
                mapOf("prefix" to statement.prefix, "newPrefix" to statement.newPrefix)
            )

            is DomainReplaceStatement.ChapterDedupSettle -> conn.exec(
                DomainReplaceSql.SETTLE_CHAPTER_INDEXES,
                mapOf("offset" to statement.offset.toString())
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

    /** 在事务里执行整批写入；异常则回滚并抛出 */
    private fun execute(): DomainReplaceCounts {
        val plan = buildPlan()
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
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }
}
