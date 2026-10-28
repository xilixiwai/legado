package io.legado.app.model

/**
 * 整本漫画下载进度的只读快照。
 *
 * 只做数值与文案换算，不依赖 Android、数据库或协程，便于 JVM 单元测试。
 * 设计约束：
 * - [percent] 只用「已完成章节数 / 总章节数」，分母固定，因此**不会倒退**；
 * - 全书图片总数在下载前不可知（需要预扫描整本正文），所以这里只暴露
 *   「当前章节图片进度」这一细粒度信息，不把未知总数当成精确值。
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
    /** 失败图片数 */
    val failedImages: Int,
    /** 整章失败的章节数（正文取不到，拿不到图片列表） */
    val failedChapters: Int,
    val running: Boolean,
) {

    /** 整体章节进度百分比，单调不减 */
    val percent: Int
        get() = if (totalChapters <= 0) {
            0
        } else {
            (doneChapters.toLong() * 100L / totalChapters).toInt().coerceIn(0, 100)
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
            append(chapterLabel).append(" 章")
            append(" · 本章 ").append(curChapterDone).append('/').append(curChapterTotal).append(" 张")
            append(" · 失败 ").append(failedImages)
            if (failedChapters > 0) {
                append(" · 章节失败 ").append(failedChapters)
            }
        }
}
