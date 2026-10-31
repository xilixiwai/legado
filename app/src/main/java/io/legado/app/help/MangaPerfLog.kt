package io.legado.app.help

import io.legado.app.utils.LogUtils
import io.legado.app.utils.MD5Utils
import java.util.concurrent.atomic.AtomicLong

/**
 * 漫画图片加载诊断日志（长期保留，旁路写入，不改变任何加载行为）。
 *
 * 通道完全复用现有机制：[LogUtils] → [io.legado.app.utils.AsyncFileHandler]（后台线程写盘）
 * → `externalCacheDir/logs/` 按天滚动 + 7 天清理；文件持久化受「记录日志」(recordLog) 开关控制。
 *
 * 分级：
 * - 一级（默认，recordLog 开启）：每次翻页一条 [MangaFlip]、每张图一条汇总 [MangaImg]；
 * - 二级（自动）：总耗时 ≥ [SLOW_THRESHOLD_MS]、失败、取消或发现异常时，追加一条阶段明细 [MangaDetail]；
 * - 三级（手动）：mangaPerfVerbose 开启后额外输出阶段起止与预取任务状态 [MangaV]。
 *
 * 保护约束：
 * - 开关关闭（或配置读取异常）时除一次布尔读外零计算、零对象分配；
 * - 所有写盘经 AsyncFileHandler 的后台线程，主线程只做字符串拼接（且仅在开关开启时）；
 * - 只记图片 URL 的 MD5 前 8 位，不记完整 URL、请求头、Cookie 或书源规则正文；
 * - 本类任何异常都不允许影响图片加载：调用方仅在 trace 交付点触达本类，且输出全部吞异常。
 */
object MangaPerfLog {

    private const val TAG = "MangaPerf"

    /** 二级明细阈值：初始诊断阈值，不是性能验收标准 */
    const val SLOW_THRESHOLD_MS = 500L

    private val flipCounter = AtomicLong(0)

    /** 真实翻页判定器：进程级单例（见 [FlipTracker] 注释），仅诊断使用 */
    val flipTracker = FlipTracker()

    /** 文件持久化总闸：复用现有「记录日志」开关；配置读取异常视为关闭 */
    val enabled: Boolean
        get() = runCatching { io.legado.app.help.config.AppConfig.recordLog }.getOrDefault(false)

    /** 三级详细诊断：需用户在「其他设置」明确开启，且仍受 recordLog 总闸约束 */
    val verbose: Boolean
        get() = runCatching {
            io.legado.app.help.config.AppConfig.recordLog &&
                    io.legado.app.help.config.AppConfig.mangaPerfVerbose
        }.getOrDefault(false)

    /** 图片短标识：MD5 前 8 位（与慢页日志既有口径一致），不记录完整 URL */
    fun imgId(url: String): String = MD5Utils.md5Encode16(url).take(8)

    /** 翻页事件 ID：会话内单调递增 */
    fun nextFlipId(): Long = flipCounter.incrementAndGet()

    /** 最近一次翻页事件 ID（预取任务在无绑定上下文时关联用） */
    fun currentFlipId(): Long = flipCounter.get()

    /** 单调时钟耗时（毫秒）：阶段计时统一口径，不受系统时间调整影响 */
    fun msSince(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    fun nowNs(): Long = System.nanoTime()

    /** 一级：翻页事件（方向/起止索引/章节标识/目标图预取与缓存状态） */
    fun flip(id: Long, dir: String, from: Int, to: Int, chapterId: String, preState: String) {
        if (!enabled) return
        runCatching {
            LogUtils.d(TAG, "MangaFlip id=$id dir=$dir $from→$to ch=$chapterId img=$preState")
        }
    }

    /** 一级：图片加载汇总（每张图一条；同一次加载含兜底链只发一条；null=诊断未开启，直接忽略） */
    fun summary(trace: Trace?) {
        if (trace == null || !enabled) return
        runCatching {
            if (trace.markEmitted()) LogUtils.d(TAG, trace.renderSummary())
        }
    }

    /** 二级：慢加载/失败/取消/异常时的阶段明细（未发生阶段记 skip，不伪造 0ms） */
    fun detail(trace: Trace?, why: String) {
        if (trace == null || !enabled) return
        runCatching {
            LogUtils.d(TAG, trace.renderDetail(why))
        }
    }

    /** 二级：首次绘制估算（OnPreDraw，非真实上屏；真实上屏用 Perfetto FrameTimeline） */
    fun draw(img: String, flipId: Long, ms: Long) {
        if (!enabled) return
        runCatching {
            LogUtils.d(TAG, "MangaDraw img=$img flip=$flipId draw=${ms}ms est")
        }
    }

    /** 三级：详细诊断（阶段起止、预取任务状态等），手动开启才有 */
    fun v(message: () -> String) {
        if (!verbose) return
        runCatching {
            LogUtils.d(TAG, "MangaV ${message()}")
        }
    }

    /**
     * 真实翻页判定（纯逻辑，JVM 可测）。
     *
     * 事件源是 WebtoonRecyclerView.onScrolled 的中心页位置变化（视图内部已按位置去重）：
     * 点击翻页、滑动、音量键、自动滚动、菜单跳页、目录跳章与章节进入最终都汇聚到这一个回调；
     * Adapter 绑定行为（首绑、复用重绑、GapWorker 预取绑定、配置变更全量重绑）不经过这里，
     * 因此不再产生虚假翻页事件，也不会污染翻页游标。
     *
     * 章节进入（含首次打开与跨章/换书）记 dir=init；同章内按页码前后记 next/prev。
     * 章节键由调用方用「书 + 章节序号」构造，换书同章号不会误判为同章。
     */
    class FlipTracker {
        private var lastChapterKey: String? = null
        private var lastPosition = -1

        /** 位置未变化（或占位行页码 -1）返回 null；from 为上一真实页码（-1=会话首个位置） */
        fun onPageChange(chapterKey: String, pageIndex: Int): Event? {
            if (pageIndex < 0) return null
            if (chapterKey != lastChapterKey) {
                val event = Event("init", lastPosition, pageIndex)
                lastChapterKey = chapterKey
                lastPosition = pageIndex
                return event
            }
            if (pageIndex == lastPosition) return null
            val event = Event(
                if (pageIndex > lastPosition) "next" else "prev",
                lastPosition,
                pageIndex
            )
            lastPosition = pageIndex
            return event
        }

        data class Event(val dir: String, val from: Int, val to: Int)
    }

    /**
     * 单次图片加载尝试的计时轨迹。由加载协程持有（预取或显示各建一个），
     * 跨线程交付点（主线程 Glide 回调/绘制）的字段用 @Volatile。
     * 纯数据+渲染，不触碰 Android 依赖，可 JVM 单测。
     */
    class Trace(
        val img: String,
        val flipId: Long,
        val src: String,
    ) {
        private val startNs = nowNs()

        /** 预取/缓存状态快照：none（无） running（预取在途） cached（本地已有） self（本轨迹即预取） */
        @Volatile
        var preState: String = "none"

        /** 缓存查找与校验（显示快路径解析，或未命中候选扫描） */
        @Volatile
        var checkMs: Long? = null

        /** 网络请求耗时（仅本次任务自己发起下载时有值） */
        @Volatile
        var netMs: Long? = null

        /** 书源图片解密耗时（仅新网络字节；规则为空时 0ms 而非 skip） */
        @Volatile
        var decryptMs: Long? = null

        /** 下载字节完整性校验耗时（bounds 级） */
        @Volatile
        var validateMs: Long? = null

        /** 落盘前 WebP 转码耗时 */
        @Volatile
        var optimizeMs: Long? = null

        /** 缓存写入耗时（含写时候选校验） */
        @Volatile
        var writeMs: Long? = null

        /** Glide 段：into() 发起 → onResourceReady/onLoadFailed 回调（含解码） */
        @Volatile
        var glideMs: Long? = null

        /** 首次绘制估算（OnPreDraw − 资源回调），仅估算值 */
        @Volatile
        var drawMs: Long? = null

        /** 可识别异常：dup-net（显示期间又发起了下载，疑似未等预取） */
        @Volatile
        var anomaly: String? = null

        /** 终态：hit/stored/bytes/net/fail/cancel */
        @Volatile
        var out: String = "fail"

        /** 失败原因（截断，不含 URL） */
        @Volatile
        var failReason: String? = null

        @Volatile
        private var emitted = false

        fun markEmitted(): Boolean = synchronized(this) {
            if (emitted) false else {
                emitted = true
                true
            }
        }

        fun elapsedMs(): Long = msSince(startNs)

        fun isSlow(): Boolean = elapsedMs() >= SLOW_THRESHOLD_MS

        fun fail(reason: String?) {
            out = "fail"
            failReason = reason?.take(80)
        }

        fun cancel(reason: String) {
            out = "cancel"
            failReason = reason
        }

        /** 汇总行（一级）：一条即可还原来源、结果、预取关联与总耗时 */
        fun renderSummary(): String = buildString {
            append("MangaImg img=").append(img)
            append(" flip=").append(flipId)
            append(" src=").append(src)
            append(" out=").append(out)
            append(" pre=").append(preState)
            append(" total=").append(elapsedMs()).append("ms")
            failReason?.takeIf { out == "fail" || out == "cancel" }?.let {
                append(" why=").append(it)
            }
        }

        /** 阶段渲染：null=未发生记 skip（不伪造 0ms），有值记 ms */
        private fun Long?.stage(): String = this?.let { "${it}ms" } ?: "skip"

        /** 明细行（二级）：阶段耗时 + 近似未归账段（含互斥等待与未插桩校验，标 ~ 表估算） */
        fun renderDetail(why: String): String {
            val accounted = listOfNotNull(
                checkMs, netMs, decryptMs, validateMs, optimizeMs, writeMs, glideMs
            ).sum()
            val waitApprox = (elapsedMs() - accounted).coerceAtLeast(0)
            return buildString {
                append("MangaDetail img=").append(img)
                append(" flip=").append(flipId)
                append(" src=").append(src)
                append(" out=").append(out)
                append(" check=").append(checkMs.stage())
                append(" net=").append(netMs.stage())
                append(" dec=").append(decryptMs.stage())
                append(" val=").append(validateMs.stage())
                append(" opt=").append(optimizeMs.stage())
                append(" write=").append(writeMs.stage())
                append(" glide=").append(glideMs.stage())
                append(" draw=").append(drawMs?.let { "${it}ms(est)" } ?: "skip")
                append(" wait~=").append(waitApprox).append("ms")
                anomaly?.let { append(" anomaly=").append(it) }
                append(" why=").append(why)
                failReason?.let { append(" err=").append(it) }
            }
        }

        companion object {
            /** 纯判定：给定时长是否触发二级明细（JVM 可测） */
            fun isSlow(totalMs: Long): Boolean = totalMs >= SLOW_THRESHOLD_MS
        }
    }
}
