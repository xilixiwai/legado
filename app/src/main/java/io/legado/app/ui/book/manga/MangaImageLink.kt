package io.legado.app.ui.book.manga

import io.legado.app.constant.SourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.getSourceType
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaPage

/** 菜单显示和内部浏览器使用同一份原始地址，不从诊断文本恢复 URL。 */
data class MangaImageLink(
    val url: String,
    val title: String,
    val sourceOrigin: String,
    val sourceName: String,
    val sourceType: Int,
)

/** 校验中心 View 的绑定身份与当前列表、书籍、章节；不发请求，也不修改 URL。 */
internal fun resolveMangaImageLink(
    page: MangaPage?,
    boundIdentity: MangaPageIdentity?,
    book: Book?,
    source: BookSource?,
    chapters: List<MangaChapter>,
): MangaImageLink? {
    if (page == null || page.mImageUrl.isBlank() || boundIdentity == null || book == null) return null
    val bookUrl = book.bookUrl
    val sourceOrigin = book.origin
    if (source != null && source.bookSourceUrl != sourceOrigin) return null
    val chapter = chapters.firstOrNull {
        it.chapter.bookUrl == bookUrl && it.chapter.index == page.chapterIndex &&
            it.pages.any { member -> member === page }
    }?.chapter ?: return null
    val identity = MangaPageIdentity.create(
        bookUrl, sourceOrigin, "${chapter.index}:${chapter.url}", page.index, page.mImageUrl
    )
    if (identity != boundIdentity) return null
    return MangaImageLink(
        url = page.mImageUrl,
        title = page.mChapterName,
        sourceOrigin = sourceOrigin,
        sourceName = source?.bookSourceName ?: book.originName,
        sourceType = source?.getSourceType() ?: SourceType.book,
    )
}
