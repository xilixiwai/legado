package io.legado.app.help.book

import io.legado.app.data.appDb
import java.util.concurrent.ConcurrentHashMap

/**
 * 章节标题重复注册表：为正文缓存键统一提供「该标题在当前目录中是否完全重复」的判定。
 *
 * 背景：正文缓存文件名原为 `%05d-index-titleMD5`，index 参与缓存键，目录刷新重排或
 * 章节序号变化会让全部缓存失效。新策略下普通章节的缓存键只含标题 MD5；
 * 标题完全重复的章节必须保留 index 前缀区分，否则同名章节会互相覆盖正文（串章）。
 * 「标题是否重复」只有对照完整目录才能回答，本注册表就是这一判定的唯一出口，
 * 保证读、写、删、标记、字数统计等所有调用点口径一致。
 *
 * 刷新时机：
 * - 网络书目录解析完成时以最新目录整体刷新（BookChapterList.analyzeChapterList）；
 * - 本地书目录重存后以最新目录整体刷新（各目录保存点）；
 * - 其余场景（进程首次访问某本书等）按需从数据库同步懒加载。
 *
 * 保守策略：注册表无法给出确定答案时（bookUrl 为空、数据库不可用）按「重复」处理，
 * 即退回带 index 的旧格式文件名——等价于旧版行为，宁可缓存不命中也不串章。
 */
object ChapterTitleRegistry {

    /** 标题加载器，可注入替换（单元测试用）。返回 null 表示无法加载 */
    @Volatile
    var titlesLoader: ((bookUrl: String) -> List<String>?)? = null

    private val dupTitlesByBook = ConcurrentHashMap<String, Set<String>>()

    /** 标题在当前目录中是否完全重复。注册表不可用时保守返回 true（按旧格式键处理） */
    fun isTitleDuplicate(bookUrl: String, title: String): Boolean {
        if (bookUrl.isEmpty()) {
            return true
        }
        val dupTitles = dupTitlesByBook[bookUrl] ?: warmAndQuery(bookUrl) ?: return true
        return title in dupTitles
    }

    /** 以完整目录刷新某本书的重复标题集（目录解析 / 目录重存后调用） */
    fun update(bookUrl: String, titles: Collection<String>) {
        if (bookUrl.isEmpty() || titles.isEmpty()) {
            // 空目录不覆盖已有判定，避免目录写入空窗期误判
            return
        }
        dupTitlesByBook[bookUrl] = computeDupTitles(titles)
    }

    fun invalidate(bookUrl: String) {
        dupTitlesByBook.remove(bookUrl)
    }

    fun invalidateAll() {
        dupTitlesByBook.clear()
    }

    private fun computeDupTitles(titles: Collection<String>): Set<String> {
        return titles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }

    /**
     * 从数据库同步加载并缓存。返回 null 表示无法确定（本次调用按重复保守处理，不缓存结果）。
     * 默认加载走 Room；项目已开启 allowMainThreadQueries，主线程调用安全（仅首次、每次会话一本一次）。
     */
    private fun warmAndQuery(bookUrl: String): Set<String>? {
        synchronized(this) {
            dupTitlesByBook[bookUrl]?.let { return it }
            val titles = try {
                titlesLoader?.invoke(bookUrl) ?: defaultLoad(bookUrl)
            } catch (_: Exception) {
                null
            } ?: return null
            val dupTitles = computeDupTitles(titles)
            dupTitlesByBook[bookUrl] = dupTitles
            return dupTitles
        }
    }

    private fun defaultLoad(bookUrl: String): List<String> {
        return appDb.bookChapterDao.getChapterTitles(bookUrl)
    }
}
