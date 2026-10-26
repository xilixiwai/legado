package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「批量替换域名」计划数与实际影响行数对照的单测。
 *
 * 对照是「预览有命中但实际没更新必须显式报告」的实现依据：
 * 任何字段不一致都要被检出，绝不能把计划/实际漂移当成成功展示。
 */
class DomainReplaceAuditTest {

    private fun planned(
        books: Int = 0,
        chapterBookUrl: Int = 0,
        chapterUrl: Int = 0,
        chapterBaseUrl: Int = 0,
        chapterResourceUrl: Int = 0,
    ) = DomainReplacePlannedCounts(
        books = books,
        chapterBookUrl = chapterBookUrl,
        chapterUrl = chapterUrl,
        chapterBaseUrl = chapterBaseUrl,
        chapterResourceUrl = chapterResourceUrl,
    )

    private fun counts(
        books: Int = 0,
        chapterBookUrl: Int = 0,
        chapterUrl: Int = 0,
        chapterBaseUrl: Int = 0,
        chapterResourceUrl: Int = 0,
    ) = DomainReplaceCounts(
        books = books,
        chapterBookUrl = chapterBookUrl,
        chapterUrl = chapterUrl,
        chapterBaseUrl = chapterBaseUrl,
        chapterResourceUrl = chapterResourceUrl,
    )

    @Test
    fun `计划为空时不视为不一致`() {
        // planned == null 表示没有可对照的计划（输入非法等提前终止）
        assertTrue(DomainReplaceAudit.compare(null, counts()).isEmpty())
    }

    @Test
    fun `计划与实际完全一致时无差异`() {
        val p = planned(books = 2, chapterBookUrl = 30, chapterUrl = 9, chapterBaseUrl = 8, chapterResourceUrl = 1)
        val c = counts(books = 2, chapterBookUrl = 30, chapterUrl = 9, chapterBaseUrl = 8, chapterResourceUrl = 1)
        assertTrue(DomainReplaceAudit.compare(p, c).isEmpty())
    }

    @Test
    fun `全部为零时无差异`() {
        assertTrue(DomainReplaceAudit.compare(planned(), counts()).isEmpty())
    }

    @Test
    fun `逐一检出每个字段的不一致`() {
        val cases = listOf(
            "books" to (planned(books = 3) to counts(books = 2)),
            "chapters.bookUrl" to (planned(chapterBookUrl = 5) to counts(chapterBookUrl = 4)),
            "chapters.url" to (planned(chapterUrl = 5) to counts(chapterUrl = 0)),
            "chapters.baseUrl" to (planned(chapterBaseUrl = 5) to counts(chapterBaseUrl = 6)),
            "chapters.resourceUrl" to (planned(chapterResourceUrl = 1) to counts(chapterResourceUrl = 0)),
        )
        cases.forEach { (field, pair) ->
            val (p, c) = pair
            val mismatches = DomainReplaceAudit.compare(p, c)
            assertEquals("应检出 $field", 1, mismatches.size)
            assertEquals(field, mismatches[0].field)
        }
    }

    @Test
    fun `计划大于零而实际全部为零被检出`() {
        // 「预览有匹配、实际零更新」的极端情形：5 个字段全部不一致，
        // 结果界面据此必须明确报告替换未完成，而不是提示成功
        val p = planned(books = 2, chapterBookUrl = 11, chapterUrl = 9, chapterBaseUrl = 9, chapterResourceUrl = 1)
        val mismatches = DomainReplaceAudit.compare(p, counts())
        assertEquals(5, mismatches.size)
        mismatches.forEach { assertEquals(0, it.actual) }
    }

    @Test
    fun `计划合计与实际合计的口径字段一致`() {
        // 防止后续加字段时只改了一侧：两侧合计覆盖的字段必须相同
        val p = planned(books = 1, chapterBookUrl = 2, chapterUrl = 3, chapterBaseUrl = 4, chapterResourceUrl = 5)
        val c = counts(books = 1, chapterBookUrl = 2, chapterUrl = 3, chapterBaseUrl = 4, chapterResourceUrl = 5)
        assertEquals(p.total, c.books + c.chapterBookUrl + c.chapterUrl + c.chapterBaseUrl + c.chapterResourceUrl)
        assertEquals(15, p.total)
    }
}
