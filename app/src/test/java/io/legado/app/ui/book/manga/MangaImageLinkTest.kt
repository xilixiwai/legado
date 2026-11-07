package io.legado.app.ui.book.manga

import io.legado.app.constant.SourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaPage
import org.junit.Assert.*
import org.junit.Test

/** 运行真实链接选择逻辑；不把这些 JVM 用例视为 Android View/触摸或 WebView 验收。 */
class MangaImageLinkTest {
    private val book = Book(bookUrl = "https://manga.test/book", origin = "manga-source", originName = "漫画源")
    private val source = BookSource(bookSourceUrl = book.origin, bookSourceName = "当前漫画源", bookSourceType = 2)
    private fun page(index: Int = 0, chapter: Int = 1, url: String = "https://image.test/$chapter/$index.jpg") =
        MangaPage(chapter, 3, url, index, 5, "第${chapter}章")

    private fun chapter(vararg pages: MangaPage, owner: Book = book, url: String = "chapter-${pages.first().chapterIndex}") =
        MangaChapter(BookChapter(url = url, bookUrl = owner.bookUrl, index = pages.first().chapterIndex),
            pages.toList(), pages.size)

    private fun identity(page: MangaPage, owner: Book = book, chapterUrl: String = "chapter-${page.chapterIndex}") =
        MangaPageIdentity.create(owner.bookUrl, owner.origin, "${page.chapterIndex}:$chapterUrl", page.index, page.mImageUrl)

    private fun resolve(
        page: MangaPage?,
        bound: MangaPageIdentity? = page?.let { identity(it) },
        chapters: List<MangaChapter> = page?.let { listOf(chapter(it)) }.orEmpty(),
        owner: Book? = book,
        activeSource: BookSource? = source,
    ) = resolveMangaImageLink(page, bound, owner, activeSource, chapters)

    @Test fun `signed encoded expired URL is passed through without normalization`() {
        val url = "https://image.test/a%2Fb.jpg?verify=1793815200-dead&token=a%2Bb+c&x=1&x=2#part%20one"
        val page = page(url = url)
        assertEquals(url, resolve(page)!!.url)
    }

    @Test fun `long URL and rule suffix remain complete for navigation`() {
        val url = "https://image.test/a.jpg?token=" + "abc%2F".repeat(8000) + ",{\"headers\":{\"Referer\":\"https://manga.test/\"}}"
        assertEquals(url, resolve(page(url = url))!!.url)
    }

    @Test fun `menu target carries manga source identity and book source category`() {
        val link = resolve(page())!!
        assertEquals(source.bookSourceUrl, link.sourceOrigin)
        assertEquals(source.bookSourceName, link.sourceName)
        assertEquals(SourceType.book, link.sourceType) // 不是 bookSourceType 的图片值 2
        assertEquals("第1章", link.title)
    }

    @Test fun `blank URL absent page missing binding and absent book hide the link`() {
        assertNull(resolve(page(url = " \n\t")))
        assertNull(resolve(null)) // 空列表、章节标题、页尾均不给出 MangaPage
        assertNull(resolve(page(), bound = null))
        assertNull(resolve(page(), owner = null))
    }

    @Test fun `unparsed or cleared chapters cannot retain a previous link`() {
        val current = page()
        assertNotNull(resolve(current))
        assertNull(resolve(current, chapters = emptyList()))
    }

    @Test fun `same adapter position with another image rejects old binding until rebound`() {
        val old = page(url = "https://image.test/old.jpg")
        val fresh = old.copy(mImageUrl = "https://image.test/new.jpg")
        assertNull(resolve(fresh, bound = identity(old)))
        assertEquals(fresh.mImageUrl, resolve(fresh, bound = identity(fresh))!!.url)
    }

    @Test fun `same image URL at another page index cannot reuse old page identity`() {
        val old = page()
        val next = old.copy(index = 1)
        assertNull(resolve(next, bound = identity(old)))
        assertNotNull(resolve(next))
    }

    @Test fun `same index and URL in another chapter cannot reuse old chapter identity`() {
        val old = page()
        val next = old.copy(chapterIndex = 2)
        assertNull(resolve(next, bound = identity(old)))
        assertNotNull(resolve(next))
    }

    @Test fun `chapter URL changes invalidate binding even at the same chapter index`() {
        val current = page()
        val chapters = listOf(chapter(current, url = "replaced-chapter"))
        assertNull(resolve(current, chapters = chapters))
        assertNotNull(resolve(current, bound = identity(current, chapterUrl = "replaced-chapter"), chapters = chapters))
    }

    @Test fun `old page object outside live chapters is rejected even when equal by value`() {
        val old = page()
        val replacement = old.copy()
        assertEquals(old, replacement)
        assertNotSame(old, replacement)
        assertNull(resolve(old, chapters = listOf(chapter(replacement))))
    }

    @Test fun `equivalent replacement can retain a bound View when diff skips rebind`() {
        val old = page()
        val replacement = old.copy()
        assertEquals(old.mImageUrl, resolve(replacement, bound = identity(old),
            chapters = listOf(chapter(replacement)))!!.url)
    }

    @Test fun `another book with equal image fields cannot inherit previous binding`() {
        val current = page()
        val other = book.copy(bookUrl = "https://manga.test/another")
        val chapters = listOf(chapter(current, owner = other))
        assertNull(resolve(current, chapters = chapters, owner = other))
        assertNotNull(resolve(current, bound = identity(current, other), chapters = chapters, owner = other))
    }

    @Test fun `source switch invalidates old binding and mismatched active source is hidden`() {
        val current = page()
        val switched = book.copy(origin = "new-manga-source")
        val newSource = source.copy(bookSourceUrl = switched.origin, bookSourceName = "新漫画源")
        assertNull(resolve(current, owner = switched, activeSource = newSource))
        assertNull(resolve(current, bound = identity(current, switched), owner = switched))
        val link = resolve(current, bound = identity(current, switched), owner = switched, activeSource = newSource)!!
        assertEquals(newSource.bookSourceUrl, link.sourceOrigin)
        assertEquals("新漫画源", link.sourceName)
    }

    @Test fun `missing source uses current book identity without borrowing another reader source`() {
        val link = resolve(page(), activeSource = null)!!
        assertEquals(book.origin, link.sourceOrigin)
        assertEquals(book.originName, link.sourceName)
        assertEquals(SourceType.book, link.sourceType)
    }

    @Test fun `center can belong to previous current or next chapter independently of reading index`() {
        val pages = (0..2).map { page(chapter = it) }
        val chapters = pages.map { chapter(it) }
        for (current in pages) {
            assertEquals(current.mImageUrl, resolve(current, chapters = chapters)!!.url)
        }
    }

    @Test fun `rapid center changes and return from browser resolve live page without retained URL`() {
        val first = page(0)
        val second = page(1)
        val chapters = listOf(chapter(first, second))
        var center: MangaPage? = first
        val readCurrent = { resolve(center, chapters = chapters) }
        val displayed = readCurrent()!!
        center = second
        assertEquals(second.mImageUrl, readCurrent()!!.url) // 点击时重读，不用 displayed
        center = null
        assertNull(readCurrent())
        center = first
        assertEquals(displayed, readCurrent()) // 返回/手动重试不替换或刷新原地址
    }
}
