package io.legado.app.ui.book.read

/**
 * 正文页“没有书源”提示判定。
 *
 * 只有同时满足以下条件才提示:
 * - 网络书籍(本地书籍不依赖网络书源);
 * - 当前书籍没有绑定书源(有书源但请求失败不属于该情况);
 * - 没有任何章节数据(已有目录或缓存正文时不提示)。
 */
internal fun shouldShowNoSourceMsg(
    isLocal: Boolean,
    hasSource: Boolean,
    chapterSize: Int,
): Boolean = !isLocal && !hasSource && chapterSize <= 0
