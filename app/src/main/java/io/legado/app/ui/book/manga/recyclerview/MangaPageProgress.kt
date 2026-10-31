package io.legado.app.ui.book.manga.recyclerview

/**
 * 单页中央加载百分比的真实性判定（纯逻辑，JVM 可测）。
 *
 * 数据来源是统一下载入口的 OkHttp 响应体真实接收字节数
 * （ProgressResponseBody → ProgressManager 按图片地址分发，主线程回调）：
 * - 只有服务端给出可靠总字节数（totalBytes > 0）时才显示百分比；
 * - 分块传输（无 Content-Length，ProgressManager 记 percentage=0）不显示，不能伪造；
 * - 缓存命中、内存兜底不产生网络下载事件，永远只显示转圈；
 * - ProgressManager 注册监听时的一次 (0,0,0) 引导调用同样被 totalBytes<=0 挡掉；
 * - 每次绑定新建实例，旧图片的进度状态不会污染新图片；相同整数百分比合并，
 *   避免逐网络分块刷 TextView。
 */
class MangaPageProgress {

    private var lastShown = -1

    /**
     * 收到一条下载进度事件（ProgressManager 已按总长算好整数百分比）。
     *
     * @return 应显示的百分比数字；null = 不显示或与当前显示相同（合并过密刷新）
     */
    fun onEvent(percentage: Int, totalBytes: Long): Int? {
        if (totalBytes <= 0) return null
        if (percentage == lastShown) return null
        lastShown = percentage
        return percentage
    }
}
