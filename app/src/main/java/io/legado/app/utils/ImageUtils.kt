package io.legado.app.utils

import com.script.ScriptBindings
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 加密图片解密工具
 */
object ImageUtils {

    /**
     * @param isCover 根据这个执行书源中不同的解密规则
     * @return 解密失败返回Null 解密规则为空不处理
     */
    fun decode(
        src: String, bytes: ByteArray, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): ByteArray? = decodeBytes(
        src, bytes, isCover, source, book,
        evaluate = { rule, bindings -> source?.evalJS(rule, bindings) },
        onFailure = { AppLog.putDebug("${src}解密错误", it) },
    )

    /** 与正式入口共用规则选择、上下文及返回值检查；允许 JVM 测试使用真实 Rhino 而不初始化 Android 存储。 */
    internal fun decodeBytes(
        src: String, bytes: ByteArray, isCover: Boolean,
        source: BaseSource?, book: Book?,
        evaluate: (String, ScriptBindings.() -> Unit) -> Any?,
        onFailure: (Throwable) -> Unit,
    ): ByteArray? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return bytes
        //解密库hutool.crypto ByteArray|InputStream -> ByteArray
        return kotlin.runCatching {
            evaluate(ruleJs) {
                put("book", book)
                put("result", bytes)
                put("src", src)
            } as ByteArray
        }.onFailure(onFailure).getOrNull()
    }

    fun decode(
        src: String, inputStream: InputStream, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): InputStream? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return inputStream
        //解密库hutool.crypto ByteArray|InputStream -> ByteArray
        return kotlin.runCatching {
            val bytes = source?.evalJS(ruleJs) {
                put("book", book)
                put("result", inputStream)
                put("src", src)
            } as ByteArray
            ByteArrayInputStream(bytes)
        }.onFailure {
            AppLog.putDebug("${src}解密错误", it)
        }.getOrNull()
    }

    fun skipDecode(source: BaseSource?, isCover: Boolean): Boolean {
        return getRuleJs(source, isCover).isNullOrBlank()
    }

    private fun getRuleJs(
        source: BaseSource?, isCover: Boolean
    ): String? {
        return when (source) {
            is BookSource ->
                if (isCover) source.coverDecodeJs
                else source.getContentRule().imageDecode

            is RssSource -> source.coverDecodeJs
            else -> null
        }
    }

}
