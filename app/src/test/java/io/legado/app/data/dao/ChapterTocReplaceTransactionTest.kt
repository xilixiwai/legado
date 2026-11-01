package io.legado.app.data.dao

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * 目录替换事务（P1-2）的语义回归。
 *
 * ## 口径（与 [RealSqliteSupport] 既有说明一致）
 *
 * JVM 上不运行 Room 生成的 DAO；这里用**生产 schema**（Room 导出的真实 DDL，
 * 含 chapters.bookUrl → books.bookUrl 外键）在真实 SQLite 引擎上验证
 * 「delByBook + insert 包进同一事务」的语义：
 * - 正常替换：新目录完整落库；
 * - 插入失败（如替换期间书籍被删导致外键失败）：删除一并回滚，原目录完整保留；
 * - 空目录替换、重复 index 的 REPLACE 写入语义与原有行为一致。
 *
 * 「11 处调用点确实包入 appDb.runInTransaction」由 Kotlin 编译与 diff 审查保证；
 * Room 事务执行层（android.database.sqlite）不在本测试覆盖范围，真机回归验收。
 */
class ChapterTocReplaceTransactionTest {

    private lateinit var conn: Connection

    private val bookUrl = "http://test.com/book/1"

    @Before
    fun setUp() {
        conn = RealSqliteSupport.open()
        seedBook(bookUrl)
        seedChapters(bookUrl, 0, 1, 2)
    }

    @After
    fun tearDown() {
        conn.close()
    }

    // -------------------------------------------------------------- 生产 SQL

    /** 与 BookChapterDao.delByBook 的 @Query 同文本 */
    private fun delByBook(target: String): Int =
        conn.exec("delete from chapters where bookUrl = :bookUrl", mapOf("bookUrl" to target))

    /** 与 BookChapterDao.insert 的 @Insert(REPLACE) 同语义（Room 生成 INSERT OR REPLACE INTO） */
    private fun insertChapter(target: String, index: Int, title: String) {
        val values = linkedMapOf(
            "bookUrl" to target,
            "url" to "$target/ch/$index.html",
            "index" to index.toString(),
            "title" to title,
            "isVolume" to "0",
            "baseUrl" to target,
            "isVip" to "0",
            "isPay" to "0",
        )
        val columns = values.keys.joinToString(", ") { "`$it`" }
        val holders = values.keys.joinToString(", ") { "?" }
        conn.prepareStatement("INSERT OR REPLACE INTO chapters ($columns) VALUES ($holders)").use { statement ->
            values.values.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            statement.executeUpdate()
        }
    }

    /** 模拟 Room runInTransaction：块内语句同一事务，异常回滚后原样抛出 */
    private inline fun <T> runInTransaction(block: () -> T): T {
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun chapterCount(target: String): Int =
        conn.scalar("select count(*) from chapters where bookUrl = :bookUrl", mapOf("bookUrl" to target))

    private fun chapterTitles(target: String): List<String> =
        conn.strings(
            "select title from chapters where bookUrl = :bookUrl order by \"index\"",
            mapOf("bookUrl" to target)
        )

    // -------------------------------------------------------------- 造数据

    private fun seedBook(target: String) {
        conn.insertBook(
            mapOf(
                "bookUrl" to target,
                "name" to "事务测试书",
                "author" to "作者",
            )
        )
    }

    private fun seedChapters(target: String, vararg indexes: Int) {
        indexes.forEach { index ->
            insertChapter(target, index, "旧章节$index")
        }
    }

    // -------------------------------------------------------------- 用例

    @Test
    fun `正常目录替换成功_新目录完整落库`() {
        runInTransaction {
            delByBook(bookUrl)
            insertChapter(bookUrl, 0, "新章节0")
            insertChapter(bookUrl, 1, "新章节1")
        }
        assertEquals(2, chapterCount(bookUrl))
        assertEquals(listOf("新章节0", "新章节1"), chapterTitles(bookUrl))
        assertTrue("回滚后外键必须完整", conn.foreignKeyViolations().isEmpty())
    }

    @Test
    fun `删除后插入失败时回滚_原目录仍完整`() {
        val ghostBookUrl = "http://ghost.invalid/book" // 不在 books 表，插入触发外键失败
        val error = assertThrows(SQLException::class.java) {
            runInTransaction {
                assertEquals(3, delByBook(bookUrl))
                insertChapter(ghostBookUrl, 0, "外键失败章节")
            }
        }
        assertEquals(3, chapterCount(bookUrl))
        assertEquals(listOf("旧章节0", "旧章节1", "旧章节2"), chapterTitles(bookUrl))
        assertTrue("回滚后外键必须完整", conn.foreignKeyViolations().isEmpty())
        assertTrue(
            "失败原因应为外键约束: ${error.message}",
            error.message?.contains("constraint", ignoreCase = true) == true
        )
    }

    @Test
    fun `空目录替换清空章节且无异常`() {
        runInTransaction {
            delByBook(bookUrl)
            // insert(*emptyList().toTypedArray()) 在 Room 生成的 vararg 实现中为空操作
        }
        assertEquals(0, chapterCount(bookUrl))
    }

    @Test
    fun `替换内重复index按REPLACE语义保留最后一行`() {
        runInTransaction {
            delByBook(bookUrl)
            insertChapter(bookUrl, 0, "重复A")
            insertChapter(bookUrl, 0, "重复B")
        }
        assertEquals(1, chapterCount(bookUrl))
        assertEquals(listOf("重复B"), chapterTitles(bookUrl))
    }
}
