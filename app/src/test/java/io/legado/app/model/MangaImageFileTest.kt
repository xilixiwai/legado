package io.legado.app.model

import io.legado.app.help.book.writeImageCandidate
import io.legado.app.utils.JvmImageFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

/** 只在 JUnit 临时目录操作；真实 PNG 解码采用 JVM ImageIO，不替代 Android/Glide 验收。 */
class MangaImageFileTest {
    @get:Rule val temp = TemporaryFolder()
    private val src = "https://cdn.test/page.PNG"
    private val base = MangaImageKeys.baseName(src)
    private val png = JvmImageFixture.png(0x80402010.toInt())
    private val replacement = JvmImageFixture.png(0xFF123456.toInt())

    private fun usable(file: File): Boolean = runCatching {
        if (MangaImageContent.isSvg(file.readBytes())) {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.tagName == "svg"
        } else {
            JvmImageFixture.read(file.inputStream()) != null
        }
    }.getOrDefault(false)

    private fun file(suffix: String, bytes: ByteArray) = File(temp.root, "$base.$suffix").apply {
        writeBytes(bytes)
    }

    private fun resolve(): String? = MangaImageContent.resolveExistingCacheName(base, "PNG") {
        usable(File(temp.root, it))
    }

    @Test
    fun `有效mcache优先于旧后缀且读取不改文件`() {
        val current = file("mcache", png)
        val legacy = file("PNG", replacement)
        assertEquals(current.name, resolve())
        assertArrayEquals(png, current.readBytes())
        assertArrayEquals(replacement, legacy.readBytes())
        assertEquals(2, temp.root.listFiles()!!.size)
    }

    @Test
    fun `损坏mcache回退有效jpg且不删除或覆盖任何候选`() {
        val bad = file("mcache", "broken".toByteArray())
        val old = file("jpg", png) //缓存内容按真实格式识别，而非文件后缀。
        assertEquals(old.name, resolve())
        assertEquals("broken", bad.readText())
        assertArrayEquals(png, old.readBytes())
    }

    @Test
    fun `只有png头没有像素数据的候选不会阻断后续旧缓存`() {
        val truncated = file("mcache", png.copyOf(24))
        assertTrue(MangaImageContent.isPng(truncated.readBytes()))
        file("jpg", png)
        assertEquals("$base.jpg", resolve())
        assertTrue(truncated.exists())
    }

    @Test
    fun `有效svg排在URL原后缀之前`() {
        file("svg", """<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><rect width="2" height="2"/></svg>""".toByteArray())
        file("PNG", png)
        assertEquals("$base.svg", resolve())
    }

    @Test
    fun `保留URL原后缀及小写后缀的既有顺序`() {
        file("PNG", png)
        file("png", replacement)
        assertEquals("$base.PNG", resolve())
        assertEquals(listOf("$base.mcache", "$base.svg", "$base.PNG", "$base.png"),
            MangaImageContent.candidatesFor(base, "PNG").take(4))
    }

    @Test
    fun `全部候选损坏时返回空且原文件完整保留`() {
        val one = file("mcache", byteArrayOf(1, 2))
        val two = file("jpg", byteArrayOf(3, 4))
        assertNull(resolve())
        assertArrayEquals(byteArrayOf(1, 2), one.readBytes())
        assertArrayEquals(byteArrayOf(3, 4), two.readBytes())
    }

    @Test
    fun `候选读取抛错仍继续查找下一个`() {
        assertEquals("$base.jpg", MangaImageContent.resolveExistingCacheName(base, "PNG") {
            if (it == "$base.mcache") throw IOException("unreadable")
            it == "$base.jpg"
        })
    }

    @Test
    fun `候选是不可读图片目录也能回退`() {
        File(temp.root, "$base.mcache").mkdir()
        file("jpg", png)
        assertEquals("$base.jpg", resolve())
        assertTrue(File(temp.root, "$base.mcache").isDirectory)
    }

    @Test
    fun `未知历史缓存命中不下载不解密不迁移`() = runBlocking {
        val bad = file("mcache", byteArrayOf(9))
        val legacy = file("PNG", png)
        val result = PersistentImageCache(store(), { error("命中不校验网络字节") },
            fetch = { error("命中不下载也不解密") }).ensure(src)
        assertEquals(ImageCacheResult.HIT, result)
        assertArrayEquals(byteArrayOf(9), bad.readBytes())
        assertArrayEquals(png, legacy.readBytes())
        assertEquals(2, temp.root.listFiles()!!.size)
    }

    @Test
    fun `首次候选校验成功后提交并清理临时文件`() {
        val target = File(temp.root, "$base.mcache")
        assertTrue(writeImageCandidate(target, png, ::usable))
        assertArrayEquals(png, target.readBytes())
        assertEquals(listOf(target.name), temp.root.list()!!.toList())
    }

    @Test
    fun `替换成功前旧文件仍可读取`() {
        val target = file("mcache", png)
        var committed = false
        assertTrue(writeImageCandidate(target, replacement, ::usable, replace = { candidate, destination ->
            assertArrayEquals(png, destination.readBytes())
            assertArrayEquals(replacement, candidate.readBytes())
            //让测试不依赖宿主平台是否支持 rename 覆盖。
            java.nio.file.Files.move(candidate.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            committed = true
            true
        }))
        assertTrue(committed)
        assertArrayEquals(replacement, target.readBytes())
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test
    fun `写入中断保留旧图且清掉自己的半截候选`() {
        val target = file("mcache", png)
        assertThrows(IOException::class.java) {
            writeImageCandidate(target, replacement, ::usable, write = { candidate, _ ->
                candidate.writeBytes(byteArrayOf(1))
                throw IOException("disk full")
            })
        }
        assertArrayEquals(png, target.readBytes())
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test
    fun `短写和无效图片都不能覆盖已有图片`() {
        val target = file("mcache", png)
        assertFalse(writeImageCandidate(target, replacement, ::usable,
            write = { candidate, _ -> candidate.writeBytes(byteArrayOf(1)) }))
        assertFalse(writeImageCandidate(target, "not an image".toByteArray(), ::usable))
        assertArrayEquals(png, target.readBytes())
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test
    fun `候选校验抛错保留旧图`() {
        val target = file("mcache", png)
        assertThrows(IOException::class.java) {
            writeImageCandidate(target, replacement, validate = { throw IOException("read failed") })
        }
        assertArrayEquals(png, target.readBytes())
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test
    fun `替换失败不能删旧图重试`() {
        val target = file("mcache", png)
        var attempts = 0
        assertFalse(writeImageCandidate(target, replacement, ::usable, replace = { _, destination ->
            attempts++
            assertArrayEquals(png, destination.readBytes())
            false
        }))
        assertEquals(1, attempts)
        assertArrayEquals(png, target.readBytes())
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test
    fun `空字节不会创建候选或改旧文件`() {
        val target = file("mcache", png)
        assertFalse(writeImageCandidate(target, byteArrayOf(), ::usable))
        assertArrayEquals(png, target.readBytes())
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test
    fun `下载失败校验失败取消和写盘失败均保留旧候选`() = runBlocking {
        val old = file("mcache", byteArrayOf(8, 9))
        val fetchers: List<suspend (String) -> ByteArray?> = listOf(
            { throw IOException("network") }, { "bad payload".toByteArray() },
            { throw CancellationException("stop") }, { png },
        )
        fetchers.forEachIndexed { index, fetch ->
            val cache = PersistentImageCache(store(failWrite = true),
                isValidData = { runCatching { JvmImageFixture.read(it.inputStream()) != null }.getOrDefault(false) },
                fetch = fetch)
            try {
                assertEquals(ImageCacheResult.FAILED, cache.ensure(src))
                assertNotEquals(2, index)
            } catch (_: CancellationException) {
                assertEquals(2, index)
            }
            assertArrayEquals(byteArrayOf(8, 9), old.readBytes())
            assertEquals(1, temp.root.listFiles()!!.size)
        }
    }

    @Test
    fun `小说插图仍允许保存无效响应以避免反复下载`() {
        //对应 BookHelp 小说分支 validate 的原有宽松策略；不改为漫画的严格图片校验。
        val target = File(temp.root, "novel.jpg")
        val error = "expired image".toByteArray()
        assertTrue(writeImageCandidate(target, error, validate = { true }))
        assertArrayEquals(error, target.readBytes())
    }

    private fun store(failWrite: Boolean = false) = object : ImageStore {
        override fun exists(key: String) = MangaImageContent.candidatesFor(base, "PNG").any {
            File(temp.root, it).exists()
        }
        override fun isUsable(key: String) = resolve() != null
        override fun delete(key: String) = error("失败和兼容查找不能删除原文件")
        override fun writeAtomic(key: String, bytes: ByteArray): Boolean =
            if (failWrite) false else writeImageCandidate(File(temp.root, "$base.mcache"), bytes, ::usable)
    }
}
