package io.legado.app.help.book

/**
 * 串行计时窗口。调用方在 globalExecutor 上传入事件发生时刻，而不是排队执行时刻。
 * 只管理窗口边界，不改变事务、累计表、每日明细或失败冲正规则。
 */
internal class ReadingSessionTimer<T>(
    private val onIdentityChanged: (T) -> Unit,
    private val onWindow: (T, Long, Long) -> Unit
) {
    var identity: T? = null
        private set
    var startedAt: Long = 0L
        private set
    private var foreground = false

    fun bind(value: T, at: Long) {
        if (identity == value) return
        checkpoint(at)
        onIdentityChanged(value)
        identity = value
        startedAt = maxOf(startedAt, at)
    }

    fun resume(at: Long) {
        if (foreground) return
        foreground = true
        startedAt = maxOf(startedAt, at)
    }

    fun pause(at: Long) {
        checkpoint(at)
        foreground = false
    }

    fun checkpoint(at: Long, includeBackground: Boolean = false) {
        if (!foreground && !includeBackground) return
        val value = identity ?: return
        val start = startedAt
        if (at <= start) return
        // 先推进窗口；失败窗口由原有待冲正队列持有，不能重复派发。
        startedAt = at
        onWindow(value, start, at)
    }

    /** 清空统计后仅重锚，不改变前后台状态。 */
    fun reanchor(at: Long) {
        startedAt = maxOf(startedAt, at)
    }

    /** 页面正在切换到尚未载入的新书，不能把加载空档记给上一本书。 */
    fun unbind(at: Long) {
        checkpoint(at)
        identity = null
        startedAt = maxOf(startedAt, at)
    }

    companion object {
        const val CHECKPOINT_INTERVAL_MS = 15_000L
    }
}

/** 不持有可变 Book 对象，排队后的旧窗口不会被切书/换源改写身份。 */
internal data class ReadingTimeIdentity(
    val name: String,
    val author: String,
    val type: Int
)
