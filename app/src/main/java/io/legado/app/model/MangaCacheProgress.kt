package io.legado.app.model

/**
 * 整本漫画下载进度的只读快照。
 *
 * 只做数值与文案换算，不依赖 Android、数据库或协程，便于 JVM 单元测试。
 * 图片数随逐章解析累加；只有全部章节的图片列表都已取得才有可靠分母。
 * 失败也算已处理，但不算成功；缓存命中与新保存分别计数。
 */
data class MangaCacheProgress(
    val bookName: String,
    /** 已处理完成的章节数（含跳过、含失败章节） */
    val doneChapters: Int,
    /** 本次任务覆盖的章节总数 */
    val totalChapters: Int,
    /** 当前章节已处理的图片数 */
    val curChapterDone: Int,
    /** 当前章节图片总数 */
    val curChapterTotal: Int,
    val discoveredImages: Int,
    val cachedImages: Int,
    val storedImages: Int,
    val totalImagesKnown: Boolean,
    /** 失败图片数 */
    val failedImages: Int,
    /** 整章失败的章节数（正文取不到，拿不到图片列表） */
    val failedChapters: Int,
    val running: Boolean,
) {

    val successImages: Int get() = cachedImages + storedImages

    val processedImages: Int get() = successImages + failedImages

    /** 总数未知或无图片时不展示确定进度，更不能用章节比例代替。 */
    val indeterminate: Boolean get() = !totalImagesKnown || discoveredImages <= 0

    val percent: Int
        get() = if (indeterminate) {
            0
        } else {
            (processedImages.toLong() * 100L / discoveredImages).coerceIn(0, 100).toInt()
        }

    /** 通知里显示的「当前章节/总章节」，运行中显示正在处理的下一章 */
    val chapterLabel: String
        get() {
            if (totalChapters <= 0) return "0/0"
            val current = if (running) doneChapters + 1 else doneChapters
            return "${current.coerceIn(0, totalChapters)}/$totalChapters"
        }

    val text: String
        get() = buildString {
            append('《').append(bookName).append("》 ")
            append("已处理 ").append(processedImages).append('/').append(discoveredImages).append(" 张")
            append(if (totalImagesKnown) "（总数）" else "（已发现，总数待定）")
            append(" · 成功 ").append(successImages)
            append("（命中 ").append(cachedImages).append("，新增 ").append(storedImages).append('）')
            append(" · 失败 ").append(failedImages)
            append(" · ").append(chapterLabel).append(" 章")
            if (failedChapters > 0) {
                append(" · 章节失败 ").append(failedChapters)
            }
        }
}
