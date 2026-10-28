package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
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
import io.legado.app.help.book.isImage
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.BookCover
import io.legado.app.model.ReadManga
import io.legado.app.utils.printOnDebug
import java.io.File

open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null

    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

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

    @SuppressLint("CheckResult")
    fun loadImageWithRetry(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        loadImage(imageUrl, isHorizontal, isLastImage, transformation, preferLocal = true)
    }

    /**
     * @param preferLocal 是否优先使用持久化缓存里的本地文件。
     * 本地文件加载失败时会自动以 [preferLocal] = false 重试一次网络地址，
     * 避免某张图缓存损坏导致整章永久打不开。
     */
    private fun loadImage(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?,
        preferLocal: Boolean,
    ) {
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, _, _ ->
            @SuppressLint("SetTextI18n")
            mProgress.text = "$percentage%"
        }
        try {
            mImage.tag = imageUrl
            val localFile = if (preferLocal) localImageFile(imageUrl) else null
            if (localFile != null) {
                //命中本地缓存：不会再有网络百分比
                mProgress.isVisible = false
            }
            val request = if (localFile != null) {
                BookCover.loadMangaLocal(context, localFile, transformation)
            } else {
                BookCover.loadManga(
                    context,
                    imageUrl,
                    sourceOrigin = ReadManga.book?.origin,
                    transformation = transformation
                )
            }
            request.addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (localFile != null) {
                        //本地文件不可读/已损坏 -> 立刻回退网络
                        loadImage(
                            imageUrl, isHorizontal, isLastImage, transformation,
                            preferLocal = false
                        )
                        return true
                    }
                    mFlProgress.isVisible = true
                    mLoading.isGone = true
                    mRetry?.isVisible = true
                    mProgress.isGone = true
                    itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    mFlProgress.isGone = true
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
                    return false
                }
            }).into(mImage)
        } catch (e: Exception) {
            e.printOnDebug()
        }

    }

    /**
     * 持久化缓存命中判定。
     *
     * 键与 [BookHelp.saveImage] 写入时完全一致（图片绝对地址的 MD5 + 后缀），
     * 因此不会把别的章节的图片错认成本章；同时要求文件非空，
     * 避免把下载中断留下的 0 字节文件当成命中。
     */
    private fun localImageFile(imageUrl: String): File? {
        val book = ReadManga.book ?: return null
        if (!book.isImage) return null
        if (!BookHelp.isImageUsable(book, imageUrl)) return null
        return BookHelp.getImage(book, imageUrl)
    }
}