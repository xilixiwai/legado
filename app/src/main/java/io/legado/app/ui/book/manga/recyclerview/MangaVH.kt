package io.legado.app.ui.book.manga.recyclerview

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
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isImage
import io.legado.app.model.BookCover
import io.legado.app.model.ImageCacheResult
import io.legado.app.model.ReadManga
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 漫画单页 ViewHolder。
 *
 * 图片加载全部走统一持久化入口 [BookHelp.ensureImage]：
 * 1. 本地有可用文件 → 直接 `Glide.load(File)`，不发任何图片网络请求；
 * 2. 本地缺失/损坏 → 由统一入口下载、校验、原子落盘，再读本地文件显示；
 * 3. 仍然失败 → 保留「重试」按钮，下次点击重新走一遍（坏文件先删，不会被永久挡住）。
 *
 * 不再把 Glide 的临时磁盘缓存当作离线手段：换 IP、清缓存、系统回收之后它就没了。
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
        val sequence = ++loadSequence
        mImage.tag = imageUrl
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        //统一入口自己下载，没有流式百分比可报
        mProgress.isVisible = false
        mRetry?.isGone = true
        val source = ReadManga.bookSource ?: book.getBookSource()
        val chapter = ReadManga.curMangaChapter?.chapter
        loadScope.launch {
            val result = try {
                BookHelp.ensureImage(source, book, imageUrl, chapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printOnDebug()
                ImageCacheResult.FAILED
            }
            withContext(Dispatchers.Main) {
                if (sequence != loadSequence || mImage.tag != imageUrl) {
                    return@withContext
                }
                if (result == ImageCacheResult.FAILED) {
                    showFail()
                } else {
                    loadFromCache(imageUrl, isHorizontal, isLastImage, transformation)
                }
            }
        }
    }

    /** 复用/销毁时取消在途加载 */
    fun cancelLoad() {
        loadSequence++
        loadScope.coroutineContext.cancelChildren()
    }

    /** 本地文件已在统一入口校验过，这里只负责解码上屏 */
    private fun loadFromCache(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?,
    ) {
        val file = localImageFile(imageUrl) ?: return showFail()
        mProgress.isVisible = false
        BookCover.loadMangaLocal(context, file, transformation)
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    //文件通过了统一校验，却在 Glide 这层解不开：删掉，避免下次继续命中
                    file.delete()
                    loadNetworkFallback(imageUrl, isHorizontal, isLastImage, transformation)
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    applyReadyLayout(isHorizontal, isLastImage)
                    return false
                }
            })
            .into(mImage)
    }

    /**
     * 最后一道兜底：本地文件连解码都失败时，用网络地址再试一次。
     * 只试一次，失败就交给用户点「重试」。
     */
    private fun loadNetworkFallback(
        imageUrl: String,
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
                showFail()
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                applyReadyLayout(isHorizontal, isLastImage)
                return false
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

    /**
     * 命中判定与统一入口同一套规则（存在 + 非空 + 可解码），
     * 避免「文件在但打不开」被当成命中。
     */
    private fun localImageFile(imageUrl: String): File? {
        val book = ReadManga.book ?: return null
        if (!book.isImage) return null
        if (!BookHelp.isImageUsable(book, imageUrl)) return null
        return BookHelp.getImage(book, imageUrl)
    }
}
