package io.legado.app.model

/**
 * 整本漫画下载进度的只读快照。
 *
 * 只做数值与文案换算，不依赖 Android、数据库或协程，便于 JVM 单元测试。
 * 图片数随逐章解析累加；只有全部章节的图片列表都已取得才有可靠分母。
 * 统计单位一律是**图片**：成功=缓存命中+新增保存，失败=最终 FAILED，
 * 正在下载=已获得并发槽的图片，等待中=已发现但尚未开始处理。
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
    /** 已获得并发槽、正在处理的图片数（任务结束后由任务侧归零） */
    val downloadingImages: Int,
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

    /** 等待中=已发现但尚未开始处理；任务结束后归零（未处理的图片不计入失败） */
    val waitingImages: Int
        get() = if (!running) {
            0
        } else {
            (discoveredImages - downloadingImages - processedImages).coerceAtLeast(0)
        }

    /** 总数未知或无图片时不展示确定进度，更不能用章节比例代替。 */
    val indeterminate: Boolean get() = !totalImagesKnown || discoveredImages <= 0

    val percent: Int
        get() = if (indeterminate) {
            0
        } else {
            (processedImages.toLong() * 100L / discoveredImages).coerceIn(0, 100).toInt()
        }

    /**
     * 收起态正文：纯四状态统计，单位均为图片。
     * 不再拼接书名——书名拼在前面会把状态数字挤到系统截断线之外。
     * 任务结束后正在下载与等待中必须归零，只保留最终成功/失败数。
     */
    val statusLine: String
        get() = "正在下载:${if (running) downloadingImages else 0}" +
                " | 等待中:$waitingImages | 失败:$failedImages | 成功:$successImages"

    /** 展开态第一行：书名；总数未定时把提示挂在书名行，不挤占状态行 */
    val headerLine: String
        get() = if (!totalImagesKnown) {
            "《$bookName》 · 总数待定"
        } else {
            "《$bookName》"
        }

    /** 展开正文：第一行书名，第二行四状态（与小说下载通知同一套状态格式） */
    val bigText: String
        get() = "$headerLine\n$statusLine"
}
