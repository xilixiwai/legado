package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookChapter

@Dao
interface BookChapterDao {

    @Query("SELECT * FROM chapters where bookUrl = :bookUrl and title like '%'||:key||'%' order by `index`")
    fun search(bookUrl: String, key: String): List<BookChapter>

    @Query("SELECT * FROM chapters where bookUrl = :bookUrl and `index` >= :start and `index` <= :end and title like '%'||:key||'%' order by `index`")
    fun search(bookUrl: String, key: String, start: Int, end: Int): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl order by `index`")
    fun getChapterList(bookUrl: String): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl and `index` >= :start and `index` <= :end order by `index`")
    fun getChapterList(bookUrl: String, start: Int, end: Int): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl and `index` = :index")
    fun getChapter(bookUrl: String, index: Int): BookChapter?

    @Query("select * from chapters where bookUrl = :bookUrl and `title` = :title")
    fun getChapter(bookUrl: String, title: String): BookChapter?

    @Query("select count(url) from chapters where bookUrl = :bookUrl")
    fun getChapterCount(bookUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookChapter: BookChapter)

    @Update
    fun update(vararg bookChapter: BookChapter)

    @Query("delete from chapters where bookUrl = :bookUrl")
    fun delByBook(bookUrl: String)

    @Query("update chapters set wordCount = :wordCount where bookUrl = :bookUrl and url = :url")
    fun upWordCount(bookUrl: String, url: String, wordCount: String)

    // 书源换域名时，同步更新章节的 bookUrl（FK: chapters.bookUrl → books.bookUrl）
    // 用 substr 前缀替换，避免 REPLACE 误替换域名内嵌子串
    @Query(
        """
        update chapters
        set bookUrl = :newUrl || substr(bookUrl, length(:oldUrl) + 1)
        where bookUrl like :oldUrl || '%'
        """
    )
    fun replaceChapterUrls(oldUrl: String, newUrl: String): Int

    // ============================================================================================
    // 「批量替换域名」
    //
    // SQL 全部集中在 [DomainReplaceSql]，DAO 与单元测试引用同一份字符串，
    // 保证测试覆盖的是生产 SQL 而不是复制品。设计要点见该文件注释。
    // ============================================================================================

    /** 按 (scheme, authority) 分组统计 chapters.url 的命中分布 */
    @Query(DomainReplaceSql.CHAPTER_URL_GROUPS)
    fun domainGroupsOfChapterUrl(): List<DomainGroupRow>

    /** 按 (scheme, authority) 分组统计 chapters.baseUrl 的命中分布 */
    @Query(DomainReplaceSql.CHAPTER_BASE_URL_GROUPS)
    fun domainGroupsOfChapterBaseUrl(): List<DomainGroupRow>

    /** 按 (scheme, authority) 分组统计 chapters.resourceUrl 的命中分布 */
    @Query(DomainReplaceSql.CHAPTER_RESOURCE_URL_GROUPS)
    fun domainGroupsOfChapterResourceUrl(): List<DomainGroupRow>

    /** 把 chapters.url 的前缀 [prefix] 整段换成 [newPrefix]（均为 `scheme://authority`） */
    @Query(DomainReplaceSql.REPLACE_CHAPTER_URL_DOMAIN)
    fun replaceChapterUrlDomain(prefix: String, newPrefix: String): Int

    /** 同 [replaceChapterUrlDomain]，作用于 chapters.baseUrl（相对章节地址的解析基准） */
    @Query(DomainReplaceSql.REPLACE_CHAPTER_BASE_URL_DOMAIN)
    fun replaceChapterBaseUrlDomain(prefix: String, newPrefix: String): Int

    /** 同 [replaceChapterUrlDomain]，作用于 chapters.resourceUrl（音频真实地址） */
    @Query(DomainReplaceSql.REPLACE_CHAPTER_RESOURCE_URL_DOMAIN)
    fun replaceChapterResourceUrlDomain(prefix: String, newPrefix: String): Int

    /** 更新 chapters.url 前的主键冲突预检，返回大于 0 表示存在冲突 */
    @Query(DomainReplaceSql.COUNT_CHAPTER_URL_CONFLICTS)
    fun countChapterUrlConflicts(prefix: String, newPrefix: String): Int

    /** 同步章节归属（FK: chapters.bookUrl → books.bookUrl） */
    @Query(DomainReplaceSql.REPLACE_CHAPTER_BOOK_URL)
    fun replaceChapterBookUrl(oldBookUrl: String, newBookUrl: String): Int

}