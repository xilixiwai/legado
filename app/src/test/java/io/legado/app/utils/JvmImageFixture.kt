package io.legado.app.utils

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Android 单测编译类路径不含 java.desktop；仅在 JVM 运行时使用 JDK 图片编解码，无新增依赖。 */
internal object JvmImageFixture {
    private val imageIO = Class.forName("javax.imageio.ImageIO")

    fun png(color: Int): ByteArray {
        val type = Class.forName("java.awt.image.BufferedImage")
        val int = Int::class.javaPrimitiveType!!
        val image = type.getConstructor(int, int, int).newInstance(2, 2, 2)
        type.getMethod("setRGB", int, int, int).invoke(image, 0, 0, color)
        return ByteArrayOutputStream().apply {
            imageIO.getMethod("write", Class.forName("java.awt.image.RenderedImage"),
                String::class.java, OutputStream::class.java).invoke(null, image, "png", this)
        }.toByteArray()
    }

    fun read(input: InputStream): Any? = input.use {
        imageIO.getMethod("read", InputStream::class.java).invoke(null, it)
    }

    fun width(image: Any): Int = image.javaClass.getMethod("getWidth").invoke(image) as Int
    fun height(image: Any): Int = image.javaClass.getMethod("getHeight").invoke(image) as Int
    fun pixel(image: Any): Int = image.javaClass.getMethod("getRGB",
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, 0, 0) as Int
}
