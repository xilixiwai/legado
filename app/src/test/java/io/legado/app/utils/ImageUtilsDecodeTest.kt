package io.legado.app.utils

import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.ImageCacheResult
import io.legado.app.model.ImageStore
import io.legado.app.model.PersistentImageCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** 真实 Rhino + 可完整解码的 PNG 样本；Android 书源环境和 BitmapFactory 仍需设备验收。 */
class ImageUtilsDecodeTest {
    private val src = "https://cdn.test/page.png?token=test"
    private val book = Book(bookUrl = "https://books.test/one", name = "规则上下文")
    private val png = JvmImageFixture.png(0x80402010.toInt())
    private var evaluated = 0
    private var failures = 0

    private fun decode(bytes: ByteArray, source: BookSource, cover: Boolean = false): ByteArray? =
        ImageUtils.decodeBytes(src, bytes, cover, source, book,
            evaluate = { rule, bindings ->
                evaluated++
                RhinoScriptEngine.eval(rule, bindings)
            },
            onFailure = { failures++ },
        )

    private fun source(rule: String? = null) = BookSource(
        bookSourceUrl = "https://books.test", ruleContent = ContentRule(imageDecode = rule),
    )

    private val xorRule = """
        if (String(src) !== 'https://cdn.test/page.png?token=test' ||
            String(book.bookUrl) !== 'https://books.test/one') throw 'wrong context';
        for (var i = 0; i < result.length; i++) result[i] = result[i] ^ 90;
        result;
    """.trimIndent()

    private fun encrypted() = png.map { (it.toInt() xor 90).toByte() }.toByteArray()

    @Test
    fun `无规则保留同一字节对象且不执行脚本`() {
        assertSame(png, decode(png, source()))
        assertEquals(0, evaluated)
        assertEquals(0, failures)
        assertSame(png, ImageUtils.decode(src, png, false, null, book))
    }

    @Test
    fun `书源解密携带原始src和书籍且结果能完整解码`() {
        val raw = encrypted()
        assertNull(JvmImageFixture.read(ByteArrayInputStream(raw)))
        val decoded = decode(raw, source(xorRule))!!
        assertArrayEquals(png, decoded)
        val image = JvmImageFixture.read(ByteArrayInputStream(decoded))!!
        assertEquals(2, JvmImageFixture.width(image))
        assertEquals(2, JvmImageFixture.height(image))
        assertEquals(0x80402010.toInt(), JvmImageFixture.pixel(image))
        assertEquals(1, evaluated)
    }

    @Test
    fun `解密脚本失败和错误返回类型都不能当成功图片`() {
        assertNull(decode(encrypted(), source("throw 'decode failed';")))
        assertNull(decode(encrypted(), source("'not bytes';")))
        assertEquals(2, failures)
    }

    @Test
    fun `解密完成后才校验优化写入且缓存命中不重复解密`() = runBlocking {
        var saved: ByteArray? = null
        var validated = 0
        var optimized = 0
        val store = object : ImageStore {
            override fun exists(key: String) = saved != null
            override fun isUsable(key: String) = saved != null
            override fun delete(key: String) = error("不能删除缓存")
            override fun writeAtomic(key: String, bytes: ByteArray): Boolean {
                assertArrayEquals(png, bytes)
                saved = bytes
                return true
            }
        }
        val cache = PersistentImageCache(store,
            isValidData = {
                validated++
                JvmImageFixture.read(ByteArrayInputStream(it)) != null
            },
            fetch = { decode(encrypted(), source(xorRule)) },
            optimize = { optimized++; assertArrayEquals(png, it); it },
        )
        assertEquals(ImageCacheResult.STORED, cache.ensure(src))
        assertEquals(ImageCacheResult.HIT, cache.ensure(src))
        assertEquals(1, evaluated)
        assertEquals(1, validated)
        assertEquals(1, optimized)
    }

    @Test
    fun `解密失败不校验不优化不写缓存`() = runBlocking {
        val store = object : ImageStore {
            override fun exists(key: String) = false
            override fun isUsable(key: String) = false
            override fun delete(key: String) = error("不能删除旧文件")
            override fun writeAtomic(key: String, bytes: ByteArray) = error("不能写入")
        }
        val cache = PersistentImageCache(store,
            isValidData = { error("失败结果不能继续校验") },
            fetch = { decode(encrypted(), source("throw 'failed';")) },
            optimize = { error("不能优化") },
        )
        assertEquals(ImageCacheResult.FAILED, cache.ensure(src))
        assertEquals(1, failures)
    }

    @Test
    fun `小说插图仍选择正文规则封面仍选择封面规则`() {
        //同一个公开 decode 方法供小说和漫画调用；正文规则与封面规则保持分开。
        val source = source("result;").apply { coverDecodeJs = "throw 'cover only';" }
        assertArrayEquals(png, decode(png, source))
        assertNull(decode(png, source, cover = true))
        assertEquals(2, evaluated)
        assertEquals(1, failures)
    }

    @Test
    fun `无规则小说输入流仍原样返回`() {
        val input = ByteArrayInputStream(png)
        assertSame(input, ImageUtils.decode(src, input, false, source(), book))
    }
}
