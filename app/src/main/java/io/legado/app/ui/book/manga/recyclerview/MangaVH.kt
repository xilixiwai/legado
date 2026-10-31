package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppLog
import io.legado.app.help.MangaPerfLog
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isImage
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.BookCover
import io.legado.app.model.ImageCacheResult
import io.legado.app.model.ReadManga
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 漫画单页 ViewHolder。
 *
 * 图片加载全部走统一持久化入口 [BookHelp.ensureImage]：
 * 1. 本地有可用文件 → 入口单次解析即校验并交接文件，直接 `Glide.load(File)`，不发任何图片网络请求；
 * 2. 本地缺失/损坏 → 由统一入口下载、解密、校验，落盘成功后读文件显示；
 * 3. 落盘失败但已获取有效字节 → 直接交给 Glide 显示，不重复联网；
 * 4. 下载、解密、校验或最终显示失败 → 保留「重试」按钮。
 *
 * 不再把 Glide 的临时磁盘缓存当作离线手段：换 IP、清缓存、系统回收之后它就没了。
 *
 * 生命周期与复用安全：
 * - 重绑时先取消旧绑定任务；复用时取消在途任务并失效旧回调（序号 + tag 双重判定）；
 * - 所有回主线程的 Glide 与 UI 操作都先过 [canDeliverToUi]：过期绑定或宿主已销毁/退出
 *   一律不再启动请求、不再触碰视图 —— 不会对已销毁的 Activity 调 `Glide.with`；
 * - Glide 监听器回调里不直接重入启动兜底请求，而是投递到主队列后重新校验身份再执行；
 * - 页面销毁只停止 UI 交接；在途图片的数据获取与安全缓存写入不因此取消。
 */
open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null

    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

    /** 单页加载作用域：滑出屏幕/被复用时取消，避免快速滑动堆积请求 */
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前绑定的在途加载任务；重绑时先取消，不为已无 UI 归属的图片继续消耗带宽 */
    private var loadJob: Job? = null

    /** 递增序号，丢弃过期回调（同一个 VH 会被反复复用） */
    private var loadSequence = 0

    /** 首帧估算用的一次性 OnPreDraw 监听（重绑/回收时移除，防止旧轨迹记到新页面的绘制帧） */
    private var drawListener: ViewTreeObserver.OnPreDrawListener? = null

    /** 当前绑定的下载进度监听地址（重绑/回收/失败时移除，不给已失效的 UI 写进度） */
    private var progressUrl: String? = null

    fun initComponent(
        loading: ProgressBar,
        image: AppCompatImageView,
        progress: TextView,
        button: Button? = null,
        flProgress: FrameLayout,
    ) {
        mLoading = loading
        mImage = image
        mRetry = button
        mProgress = progress
        mFlProgress = flProgress
    }

    fun loadImageWithRetry(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        val book = ReadManga.book
        if (book == null || !book.isImage || imageUrl.isBlank()) {
            showFail()
            return
        }
        //重绑先取消旧绑定任务：其结果已无 UI 归属；一并移除旧的首帧估算监听，
        //避免重绑后旧轨迹把新页面的绘制帧记成自己的 draw 估算
        loadJob?.cancel()
        removeDrawListener()
        val sequence = ++loadSequence
        mImage.tag = imageUrl
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        //中央百分比只反映统一下载入口的真实字节进度：
        //缓存命中/内存兜底不产生下载事件，此时只保留转圈，不显示虚假百分比
        mProgress.isVisible = false
        mRetry?.isGone = true
        registerDownloadProgress(imageUrl, sequence)
        val source = ReadManga.bookSource ?: book.getBookSource()
        val chapter = ReadManga.curMangaChapter?.chapter
        //诊断轨迹：只在记录日志开启时创建；pre 快照区分预取在途/已有缓存/全新下载
        val trace = if (MangaPerfLog.enabled) {
            MangaPerfLog.Trace(
                MangaPerfLog.imgId(imageUrl), MangaPerfLog.currentFlipId(), src = "display"
            ).apply { preState = BookHelp.preloadStateOf(book, imageUrl) }
        } else null
        loadJob = loadScope.launch {
            try {
                var imageData: ByteArray? = null
                var hitFile: File? = null
                val ensureStart = System.nanoTime()
                val result = try {
                    BookHelp.ensureImage(
                        source, book, imageUrl, chapter,
                        onImageData = { imageData = it },
                        onHitFile = { hitFile = it },
                        trace = trace,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printOnDebug()
                    ImageCacheResult.FAILED
                }
                val ensureMs = (System.nanoTime() - ensureStart) / 1_000_000
                //异常识别：快照时预取在途、本任务却又发起了下载 → 疑似未等预取
                if (trace != null && trace.netMs != null && trace.preState == "running") {
                    trace.anomaly = "dup-net"
                }
                withContext(Dispatchers.Main) {
                    if (!canDeliverToUi(sequence, imageUrl)) {
                        trace?.cancel("stale")
                        MangaPerfLog.summary(trace)
                        return@withContext
                    }
                    val bytes = imageData
                    if (result != ImageCacheResult.FAILED) {
                        if (trace != null) {
                            trace.out = if (result == ImageCacheResult.HIT) "hit" else "stored"
                        }
                        loadFromCache(
                            imageUrl, sequence, hitFile, result, ensureMs,
                            isHorizontal, isLastImage, transformation, trace
                        )
                    } else if (bytes != null) {
                        if (trace != null) trace.out = "bytes"
                        loadFromBytes(
                            imageUrl, sequence, bytes, result, ensureMs,
                            isHorizontal, isLastImage, transformation, trace
                        )
                    } else {
                        if (trace != null) {
                            trace.fail("ensure failed")
                            MangaPerfLog.summary(trace)
                            MangaPerfLog.detail(trace, "fail")
                        }
                        showFail()
                    }
                }
            } catch (e: CancellationException) {
                //取消（重绑/复用回收/页面退出）：与失败严格区分记录
                trace?.cancel("cancel")
                MangaPerfLog.summary(trace)
                throw e
            }
        }
    }

    /** 复用/销毁时取消在途加载 */
    fun cancelLoad() {
        loadSequence++
        removeProgressListener()
        removeDrawListener()
        loadJob?.cancel()
        loadJob = null
    }

    /**
     * 中央加载百分比（原有 UI 通路的恢复）：监听统一下载入口的真实字节进度。
     * ProgressResponseBody 在主线程回调，这里只做整数百分比合并与 TextView 更新；
     * 每次绑定先移除旧监听并新建判定器，旧图片进度不会污染新图片；
     * 回调先过 [canDeliverToUi]，重绑/回收/失败后的过期事件一律不触碰 UI。
     */
    @SuppressLint("SetTextI18n")
    private fun registerDownloadProgress(imageUrl: String, sequence: Int) {
        removeProgressListener()
        progressUrl = imageUrl
        val progress = MangaPageProgress()
        ProgressManager.addListener(imageUrl) { _, percentage, _, totalBytes ->
            if (!canDeliverToUi(sequence, imageUrl)) return@addListener
            progress.onEvent(percentage, totalBytes)?.let { pct ->
                mProgress.isVisible = true
                mProgress.text = "$pct%"
            }
        }
    }

    private fun removeProgressListener() {
        progressUrl?.let { ProgressManager.removeListener(it) }
        progressUrl = null
    }

    /** 移除未触发的首帧估算监听（幂等；监听自身触发时也会摘除并清引用） */
    private fun removeDrawListener() {
        drawListener?.let {
            mImage.viewTreeObserver.removeOnPreDrawListener(it)
        }
        drawListener = null
    }

    /**
     * 绑定身份 + 宿主生命周期双重守卫：过期绑定或宿主已销毁/退出，
     * 一律不再触碰 Glide 与页面 UI —— 保证不会对已销毁的 Activity 调 `Glide.with`。
     */
    private fun canDeliverToUi(sequence: Int, imageUrl: String): Boolean {
        if (sequence != loadSequence || mImage.tag != imageUrl) return false
        val activity = context as? Activity ?: return true
        return !activity.isDestroyed && !activity.isFinishing
    }

    /**
     * 偏慢页面的耗时诊断：ensure 段覆盖「缓存查找和校验」或「首次网络获取（含解密/写入）」，
     * glide 段覆盖「解码和显示」。脱敏：只记 URL 的 MD5 前 8 位，不记完整地址。
     */
    private fun logSlowLoad(
        imageUrl: String,
        result: ImageCacheResult,
        ensureMs: Long,
        glideMs: Long,
    ) {
        if (ensureMs + glideMs < SLOW_PAGE_LOG_THRESHOLD_MS) return
        val phase = when (result) {
            ImageCacheResult.HIT -> "缓存命中"
            ImageCacheResult.STORED -> "网络获取"
            ImageCacheResult.FAILED -> "内存兜底"
        }
        AppLog.put(
            "漫画页加载偏慢:$phase ensure=${ensureMs}ms glide=${glideMs}ms " +
                    "img=${MD5Utils.md5Encode16(imageUrl).take(8)}"
        )
    }

    /** 只使用本次已解密且通过校验的数据；缓存失败不触发另一轮网络加载。 */
    private fun loadFromBytes(
        imageUrl: String,
        sequence: Int,
        bytes: ByteArray,
        result: ImageCacheResult,
        ensureMs: Long,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?,
        trace: MangaPerfLog.Trace?,
    ) {
        var builder = ImageLoader.load(context, bytes)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
        if (transformation != null) builder = builder.transform(transformation)
        val glideStart = System.nanoTime()
        builder.addListener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean {
                logSlowLoad(imageUrl, result, ensureMs, (System.nanoTime() - glideStart) / 1_000_000)
                emitFail(trace, glideStart, e?.localizedMessage ?: "bytes decode failed")
                if (canDeliverToUi(sequence, imageUrl)) showFail()
                return true
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                logSlowLoad(imageUrl, result, ensureMs, (System.nanoTime() - glideStart) / 1_000_000)
                val deliver = canDeliverToUi(sequence, imageUrl)
                if (deliver) {
                    applyReadyLayout(isHorizontal, isLastImage)
                    emitReady(trace, glideStart, sequence, imageUrl)
                } else {
                    trace?.cancel("stale")
                    MangaPerfLog.summary(trace)
                }
                return !deliver
            }
        }).into(mImage)
    }

    /**
     * 命中文件已由统一入口单次解析并完整校验，这里只负责解码上屏。
     * 仅在快路径未交接（并发下载刚写完等极少数情况）时，才做一次单次解析兜底。
     */
    private fun loadFromCache(
        imageUrl: String,
        sequence: Int,
        hitFile: File?,
        result: ImageCacheResult,
        ensureMs: Long,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?,
        trace: MangaPerfLog.Trace?,
    ) {
        val file = hitFile ?: run {
            val book = ReadManga.book
            if (book == null || !book.isImage) return showFail()
            BookHelp.getValidatedImageFile(book, imageUrl)
        } ?: return showFail()
        mProgress.isVisible = false
        val glideStart = System.nanoTime()
        BookCover.loadMangaLocal(context, file, transformation)
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    logSlowLoad(imageUrl, result, ensureMs, (System.nanoTime() - glideStart) / 1_000_000)
                    if (trace != null) {
                        trace.glideMs = MangaPerfLog.msSince(glideStart)
                        trace.fail("glide decode failed")
                    }
                    if (!canDeliverToUi(sequence, imageUrl)) return true
                    //文件通过了统一校验，却在 Glide 这层解不开：删掉，避免下次继续命中
                    file.delete()
                    //不在监听器回调里直接重入 Glide：投递到主队列，执行前重新校验身份与生命周期
                    mImage.post {
                        if (canDeliverToUi(sequence, imageUrl)) {
                            loadNetworkFallback(
                                imageUrl, sequence, isHorizontal, isLastImage, transformation, trace
                            )
                        } else {
                            trace?.cancel("stale")
                            MangaPerfLog.summary(trace)
                        }
                    }
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    logSlowLoad(imageUrl, result, ensureMs, (System.nanoTime() - glideStart) / 1_000_000)
                    val deliver = canDeliverToUi(sequence, imageUrl)
                    if (deliver) {
                        applyReadyLayout(isHorizontal, isLastImage)
                        emitReady(trace, glideStart, sequence, imageUrl)
                    } else {
                        trace?.cancel("stale")
                        MangaPerfLog.summary(trace)
                    }
                    return !deliver
                }
            })
            .into(mImage)
    }

    /**
     * 最后一道兜底：本地文件连解码都失败时，用网络地址再试一次。
     * 只试一次，失败就交给用户点「重试」。入口处已校验绑定身份与生命周期。
     */
    private fun loadNetworkFallback(
        imageUrl: String,
        sequence: Int,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?,
        trace: MangaPerfLog.Trace? = null,
    ) {
        val glideStart = System.nanoTime()
        BookCover.loadManga(
            context,
            imageUrl,
            sourceOrigin = ReadManga.book?.origin,
            transformation = transformation
        ).addListener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean {
                emitFail(trace, glideStart, e?.localizedMessage ?: "network fallback failed")
                if (canDeliverToUi(sequence, imageUrl)) showFail()
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                val deliver = canDeliverToUi(sequence, imageUrl)
                if (deliver) {
                    if (trace != null) trace.out = "net"
                    applyReadyLayout(isHorizontal, isLastImage)
                    emitReady(trace, glideStart, sequence, imageUrl)
                } else {
                    trace?.cancel("stale")
                    MangaPerfLog.summary(trace)
                }
                return !deliver
            }
        }).into(mImage)
    }

    /**
     * 成功交付终端：记录 glide 段耗时并发汇总行（一次加载只发一条，含兜底链）；
     * 慢页（≥[MangaPerfLog.SLOW_THRESHOLD_MS]）或详细模式再发阶段明细，
     * 并登记 OnPreDraw 首帧估算（est，非真实上屏时刻）。
     * 带上 [sequence]/[imageUrl]：preDraw 触发时重校验绑定身份，
     * ViewHolder 已重绑或图片已过期时不得把新页面的绘制帧记给旧轨迹。
     */
    private fun emitReady(
        trace: MangaPerfLog.Trace?,
        glideStart: Long,
        sequence: Int,
        imageUrl: String,
    ) {
        if (trace == null) return
        trace.glideMs = MangaPerfLog.msSince(glideStart)
        MangaPerfLog.summary(trace)
        if (trace.isSlow() || MangaPerfLog.verbose) {
            MangaPerfLog.detail(trace, if (trace.isSlow()) "slow" else "verbose")
            val readyNs = System.nanoTime()
            //一次性 OnPreDraw：资源就绪到下一绘制帧的间隔（估算值，非真实上屏时刻）。
            //触发即摘除；记账前重校验绑定身份（序号 + tag），过期一律不记、静默返回
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                private var fired = false
                override fun onPreDraw(): Boolean {
                    if (fired) return true
                    fired = true
                    mImage.viewTreeObserver.removeOnPreDrawListener(this)
                    if (drawListener === this) drawListener = null
                    if (!canDeliverToUi(sequence, imageUrl)) return true
                    val drawMs = MangaPerfLog.msSince(readyNs)
                    trace.drawMs = drawMs
                    MangaPerfLog.draw(trace.img, trace.flipId, drawMs)
                    return true
                }
            }
            drawListener = listener
            mImage.viewTreeObserver.addOnPreDrawListener(listener)
        }
    }

    /** 失败终端：记录 glide 段耗时、置失败原因并发汇总 + 明细（emit 幂等，兜底链只发一次） */
    private fun emitFail(trace: MangaPerfLog.Trace?, glideStart: Long, reason: String) {
        if (trace == null) return
        trace.glideMs = MangaPerfLog.msSince(glideStart)
        trace.fail(reason)
        MangaPerfLog.summary(trace)
        MangaPerfLog.detail(trace, "fail")
    }

    private fun applyReadyLayout(isHorizontal: Boolean, isLastImage: Boolean) {
        mFlProgress.isGone = true
        mProgress.isVisible = false
        if (!isHorizontal) {
            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = Gravity.NO_GRAVITY
            }
            if (isLastImage) {
                mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                itemView.minimumHeight = minHeight
            } else {
                mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                itemView.minimumHeight = 0
            }
            mImage.scaleType = ImageView.ScaleType.FIT_XY
        } else {
            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            itemView.minimumHeight = 0
            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER
            }
            mImage.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun showFail() {
        mFlProgress.isVisible = true
        mLoading.isGone = true
        mRetry?.isVisible = true
        mProgress.isGone = true
        removeProgressListener()
        itemView.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    companion object {
        /** 页面总耗时（ensure + glide）超过该值才记入 AppLog，避免逐页刷日志 */
        private const val SLOW_PAGE_LOG_THRESHOLD_MS = 300L
    }
}
