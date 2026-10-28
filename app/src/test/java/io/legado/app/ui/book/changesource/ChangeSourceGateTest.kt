package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源列表点击门禁的行为测试。
 *
 * 判定函数 [isDuplicateSelection] 是纯函数，直接在 JVM 上验证四种场景，
 * 不依赖 RecyclerView / Activity / 数据库。
 *
 * 背景：`books.bookUrl` 与 `books.origin` 是两个独立字段，「批量替换域名」只改
 * `bookUrl` / `tocUrl`、不动 `origin`。换域名后旧书的 `bookUrl` 已是新域名，
 * 新书源返回的候选地址与它完全相同，只看 URL 会把「同一本书、不同书源」
 * 误判为重复点击。
 */
class ChangeSourceGateTest {

    private val newDomain = "https://www.banzhu11111111.net"
    private val oldDomain = "https://www.banzhu9999999.net"
    private val bookPath = "/48/48412/"

    /** 场景 A：地址相同、书源归属相同 → 重复选择，跳过 */
    @Test
    fun `场景A 地址与归属都相同应跳过`() {
        val url = newDomain + bookPath
        assertTrue(isDuplicateSelection(url, newDomain, url, newDomain))
    }

    /** 场景 B：地址相同、书源归属不同 → 必须放行（本次修复的核心） */
    @Test
    fun `场景B 地址相同但归属不同应放行`() {
        val url = newDomain + bookPath
        assertFalse(isDuplicateSelection(url, newDomain, url, oldDomain))
    }

    /** 场景 C：地址不同 → 放行，与归属是否相同无关 */
    @Test
    fun `场景C 地址不同应放行`() {
        assertFalse(
            isDuplicateSelection(newDomain + bookPath, newDomain, oldDomain + bookPath, newDomain)
        )
        assertFalse(
            isDuplicateSelection(newDomain + bookPath, newDomain, oldDomain + bookPath, oldDomain)
        )
    }

    /**
     * 场景 D：`oldBookOrigin == null`。
     *
     * 真实调用链里 `oldBookUrl` 与 `oldBookOrigin` 同源于 `callBack?.oldBook`
     * （`ChangeBookSourceDialog.kt:351-355`），所以二者要么同时为 null，
     * 要么同时非 null（`Book.origin` 是非空字段，默认 `loc_book`）。
     * 二者同时为 null 时，候选的 `bookUrl` 一定不等于 null，原门禁本来就放行，
     * 新判定结果与原行为完全一致。
     */
    @Test
    fun `场景D 当前书籍不存在时行为与原门禁一致`() {
        // 详情页/阅读页可能在没有 oldBook 时打开换源列表
        assertFalse(isDuplicateSelection(newDomain + bookPath, newDomain, null, null))
        assertFalse(isDuplicateSelection("", "", null, null))
    }

    /** 场景 D 补充：本地书的 origin 是 loc_book，与任何网络候选都不同 → 放行 */
    @Test
    fun `场景D 本地书不会被误判为重复`() {
        val localTag = "loc_book"
        // 本地书 bookUrl 是 content:// 形式，与网络候选天然不同
        assertFalse(
            isDuplicateSelection(
                newDomain + bookPath, newDomain,
                "content://com.android.externalstorage.documents/document/1%3Abook.txt", localTag
            )
        )
        // 即使极端情况下 URL 相同，归属不同也应放行
        assertFalse(isDuplicateSelection(newDomain + bookPath, newDomain, newDomain + bookPath, localTag))
    }

    /** 补充：历史数据里 origin 为空串时，不能被当成「与候选相同」而跳过 */
    @Test
    fun `归属为空串时不算重复`() {
        val url = newDomain + bookPath
        assertFalse(isDuplicateSelection(url, newDomain, url, ""))
    }

    /** 补充：两侧都为 null 且地址相同 → 仍是重复，保持对称语义 */
    @Test
    fun `两侧归属都为空且地址相同算重复`() {
        val url = newDomain + bookPath
        assertTrue(isDuplicateSelection(url, null, url, null))
    }

    /**
     * 场景 E 的可测部分：换源链路把候选书源归属带进新 Book。
     *
     * `ChangeBookSourceDialog.changeSource()` 取 `viewModel.bookMap[key] ?: searchBook.toBook()`，
     * 而 `bookMap` 里的 Book 也是由 `searchBook.toBook()` 生成
     * （`ChangeBookSourceViewModel.kt:288 / 320`）。因此只要 `toBook()` 保留 origin，
     * 换源落库后的 `books.origin` 就是候选书源归属。
     *
     * 真正的落库与阅读状态迁移（`ReadBookViewModel.changeTo` → `migrateTo` → `insert`）
     * 需要真机验证，见报告。
     */
    @Test
    fun `场景E 候选书籍转Book后保留候选书源归属`() {
        val candidate = SearchBook(
            bookUrl = newDomain + bookPath,
            origin = newDomain,
            originName = "笔趣阁",
            name = "超维进化曲",
            author = "某作者"
        )
        val book = candidate.toBook()
        assertEquals(newDomain, book.origin)
        assertEquals(newDomain + bookPath, book.bookUrl)
        assertEquals("笔趣阁", book.originName)
    }
}
