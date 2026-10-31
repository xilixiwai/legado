package io.legado.app.ui.book.manga.recyclerview

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
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
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isImage
import io.legado.app.help.glide.ImageLoader
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
        //重绑先取消旧绑定任务：其结果已无 UI 归属
        loadJob?.cancel()
        val sequence = ++loadSequence
        mImage.tag = imageUrl
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        //统一入口自己下载，没有流式百分比可报
        mProgress.isVisible = false
        mRetry?.isGone = true
        val source = ReadManga.bookSource ?: book.getBookSource()
        val chapter = ReadManga.curMangaChapter?.chapter
        loadJob = loadScope.launch {
            var imageData: ByteArray? = null
            var hitFile: File? = null
            val ensureStart = System.nanoTime()
            val result = try {
                BookHelp.ensureImage(
                    source, book, imageUrl, chapter,
                    onImageData = { imageData = it },
                    onHitFile = { hitFile = it },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printOnDebug()
                ImageCacheResult.FAILED
            }
            val ensureMs = (System.nanoTime() - ensureStart) / 1_000_000
            withContext(Dispatchers.Main) {
                if (!canDeliverToUi(sequence, imageUrl)) {
                    return@withContext
                }
                val bytes = imageData
                if (result != ImageCacheResult.FAILED) {
                    loadFromCache(
                        imageUrl, sequence, hitFile, result, ensureMs,
                        isHorizontal, isLastImage, transformation
                    )
                } else if (bytes != null) {
                    loadFromBytes(
                        imageUrl, sequence, bytes, result, ensureMs,
                        isHorizontal, isLastImage, transformation
                    )
                } else {
                    showFail()
                }
            }
        }
    }

    /** 复用/销毁时取消在途加载 */
    fun cancelLoad() {
        loadSequence++
        loadJob?.cancel()
        loadJob = null
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
                if (deliver) applyReadyLayout(isHorizontal, isLastImage)
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
                    if (!canDeliverToUi(sequence, imageUrl)) return true
                    //文件通过了统一校验，却在 Glide 这层解不开：删掉，避免下次继续命中
                    file.delete()
                    //不在监听器回调里直接重入 Glide：投递到主队列，执行前重新校验身份与生命周期
                    mImage.post {
                        if (canDeliverToUi(sequence, imageUrl)) {
                            loadNetworkFallback(imageUrl, sequence, isHorizontal, isLastImage, transformation)
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
                    if (deliver) applyReadyLayout(isHorizontal, isLastImage)
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
    ) {
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
                if (deliver) applyReadyLayout(isHorizontal, isLastImage)
                return !deliver
            }
        }).into(mImage)
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
        itemView.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    companion object {
        /** 页面总耗时（ensure + glide）超过该值才记入 AppLog，避免逐页刷日志 */
        private const val SLOW_PAGE_LOG_THRESHOLD_MS = 300L
    }
}
