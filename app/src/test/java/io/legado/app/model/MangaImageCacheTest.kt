package io.legado.app.model

import io.legado.app.data.entities.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 统一持久化图片缓存的纯逻辑测试。
 *
 * 覆盖的是「下载 → 校验 → 原子落盘 → 命中」这条契约本身，
 * 用假存储/假下载构造真实环境难以稳定复现的场景（错误页、空响应、写盘失败、取消）。
 *
 * 真实相册目录、Glide 解码、通知渲染仍由真机验收，清单见交付报告。
 */
class MangaImageCacheTest {

    /** 用 PNG 头当「有效图片」的替身，避免依赖 Android BitmapFactory */
    private val validImage = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03
    )

    private val htmlErrorPage = "<html><body>403 Forbidden</body></html>".toByteArray()

    private fun isValidImage(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
    }

    private class FakeStore : ImageStore {
        val files = LinkedHashMap<String, ByteArray>()
        var failWrites = false
        var deletes = 0

        override fun exists(key: String): Boolean = files.containsKey(key)

        override fun isUsable(key: String): Boolean {
            val bytes = files[key] ?: return false
            return bytes.size >= 8 && bytes[0] == 0x89.toByte()
        }

        override fun delete(key: String) {
            if (files.remove(key) != null) deletes++
        }

        override fun writeAtomic(key: String, bytes: ByteArray): Boolean {
            if (failWrites) return false
            files[key] = bytes
            return true
        }
    }

    private fun cache(
        store: ImageStore,
        fetch: suspend (String) -> ByteArray?,
    ) = PersistentImageCache(
        store = store,
        isValidData = ::isValidImage,
        fetch = fetch,
    )

    // ---------- 1. 首次落盘 / 二次命中 ----------

    @Test
    fun `首次加载成功写入持久化目录`() {
        runBlocking {
            val store = FakeStore()
            var fetched = 0
            val result = cache(store) {
                fetched++
                validImage
            }.ensure("https://a.test/p/1.jpg")
            assertEquals(ImageCacheResult.STORED, result)
            assertEquals(1, fetched)
            assertTrue(store.isUsable("https://a.test/p/1.jpg"))
        }
    }

    @Test
    fun `第二次加载命中本地且不再发起下载`() {
        runBlocking {
            val store = FakeStore()
            var fetched = 0
            val c = cache(store) {
                fetched++
                validImage
            }
            assertEquals(ImageCacheResult.STORED, c.ensure("https://a.test/p/1.jpg"))
            assertEquals(ImageCacheResult.HIT, c.ensure("https://a.test/p/1.jpg"))
            assertEquals(ImageCacheResult.HIT, c.ensure("https://a.test/p/1.jpg"))
            assertEquals("命中本地后不能再下载", 1, fetched)
        }
    }

    // ---------- 2. 坏数据不得算成功 ----------

    @Test
    fun `HTTP错误页不会被判定为缓存成功`() {
        runBlocking {
            val store = FakeStore()
            val result = cache(store) { htmlErrorPage }.ensure("https://a.test/p/2.jpg")
            assertEquals(ImageCacheResult.FAILED, result)
            assertFalse("错误页不能落盘", store.exists("https://a.test/p/2.jpg"))
        }
    }

    @Test
    fun `空响应不会被判定为缓存成功`() {
        runBlocking {
            val store = FakeStore()
            assertEquals(
                ImageCacheResult.FAILED,
                cache(store) { ByteArray(0) }.ensure("https://a.test/p/3.jpg")
            )
            assertFalse(store.exists("https://a.test/p/3.jpg"))
        }
    }

    @Test
    fun `下载返回空指针时计为失败`() {
        runBlocking {
            val store = FakeStore()
            assertEquals(
                ImageCacheResult.FAILED,
                cache(store) { null }.ensure("https://a.test/p/4.jpg")
            )
            assertTrue(store.files.isEmpty())
        }
    }

    @Test
    fun `写盘失败不算缓存成功`() {
        runBlocking {
            val store = FakeStore().apply { failWrites = true }
            assertEquals(
                ImageCacheResult.FAILED,
                cache(store) { validImage }.ensure("https://a.test/p/5.jpg")
            )
            assertFalse(store.exists("https://a.test/p/5.jpg"))
        }
    }

    @Test
    fun `下载异常计为失败且不向调用方抛出`() {
        runBlocking {
            val store = FakeStore()
            val result = cache(store) { throw IllegalStateException("连接被重置") }
                .ensure("https://a.test/p/6.jpg")
            assertEquals(ImageCacheResult.FAILED, result)
            assertTrue(store.files.isEmpty())
        }
    }

    @Test
    fun `取消异常向上传播且不写盘`() {
        val store = FakeStore()
        try {
            runBlocking {
                cache(store) { throw CancellationException("任务取消") }
                    .ensure("https://a.test/p/7.jpg")
            }
            fail("取消必须向上传播，否则下载任务停不下来")
        } catch (_: CancellationException) {
            assertTrue(store.files.isEmpty())
        }
    }

    // ---------- 3. 坏文件可恢复 ----------

    @Test
    fun `已有损坏文件会先删除再重新下载`() {
        runBlocking {
            val store = FakeStore()
            store.files["https://a.test/p/8.jpg"] = htmlErrorPage
            var fetched = 0
            val result = cache(store) {
                fetched++
                validImage
            }.ensure("https://a.test/p/8.jpg")
            assertEquals(ImageCacheResult.STORED, result)
            assertEquals(1, fetched)
            assertEquals("坏文件必须被删掉", 1, store.deletes)
            assertTrue(store.isUsable("https://a.test/p/8.jpg"))
        }
    }

    @Test
    fun `有效缓存不会因为再次调用而被删除`() {
        runBlocking {
            val store = FakeStore()
            store.files["https://a.test/p/9.jpg"] = validImage
            val result = cache(store) { throw AssertionError("命中时不应下载") }
                .ensure("https://a.test/p/9.jpg")
            assertEquals(ImageCacheResult.HIT, result)
            assertEquals(0, store.deletes)
        }
    }

    // ---------- 4. 去重 ----------

    @Test
    fun `同一张图并发请求只下载一次`() {
        runBlocking {
            val store = FakeStore()
            var fetched = 0
            val c = cache(store) {
                fetched++
                delay(20)
                validImage
            }
            val results = (1..8).map {
                async(Dispatchers.Default) { c.ensure("https://a.test/p/10.jpg") }
            }.awaitAll()
            assertEquals(1, fetched)
            assertEquals(1, results.count { it == ImageCacheResult.STORED })
            assertEquals(7, results.count { it == ImageCacheResult.HIT })
        }
    }

    // ---------- 5. 批量：处理单位是图片，不是章节 ----------

    private suspend fun runBatch(
        urls: List<String>,
        concurrency: Int = 4,
    ): Triple<ImageBatchStats, List<String>, Int> {
        val processed = mutableListOf<String>()
        var maxInFlight = 0
        val inFlight = AtomicInteger(0)
        val stats = ensureImageBatch(
            urls = urls,
            concurrency = concurrency,
            ensure = { url ->
                val now = inFlight.incrementAndGet()
                maxInFlight = max(maxInFlight, now)
                delay(2)
                synchronized(processed) { processed.add(url) }
                inFlight.decrementAndGet()
                ImageCacheResult.STORED
            },
        )
        return Triple(stats, processed, maxInFlight)
    }

    @Test
    fun `单张图片的章节也会被处理`() {
        runBlocking {
            val (stats, processed, _) = runBatch(listOf("https://a.test/p/1.jpg"))
            assertEquals(1, stats.total)
            assertEquals(1, stats.stored)
            assertEquals(listOf("https://a.test/p/1.jpg"), processed)
        }
    }

    @Test
    fun `单章三百张图片逐张全部处理`() {
        runBlocking {
            val urls = (1..300).map { "https://a.test/p/$it.jpg" }
            val (stats, processed, _) = runBatch(urls)
            assertEquals(300, stats.total)
            assertEquals(300, stats.stored)
            assertEquals(0, stats.failed)
            assertTrue(stats.allProcessed)
            assertEquals("必须逐张处理，不能只取首张", urls.toSet(), processed.toSet())
        }
    }

    @Test
    fun `多章节多图片累计正确`() {
        runBlocking {
            var total = ImageBatchStats(0)
            for (chapter in 1..3) {
                val urls = (1..7).map { "https://a.test/c$chapter/$it.jpg" }
                val stats = ensureImageBatch(urls, 3) { ImageCacheResult.HIT }
                assertEquals(7, stats.hit)
                total = ImageBatchStats(
                    total = total.total + stats.total,
                    hit = total.hit + stats.hit,
                    stored = total.stored + stats.stored,
                    failed = total.failed + stats.failed,
                )
            }
            assertEquals(21, total.total)
            assertEquals(21, total.done)
        }
    }

    @Test
    fun `批量并发不超过上限`() {
        runBlocking {
            val urls = (1..40).map { "https://a.test/p/$it.jpg" }
            val (_, _, maxInFlight) = runBatch(urls, concurrency = 4)
            assertTrue("并发峰值 $maxInFlight 超过上限 4", maxInFlight <= 4)
        }
    }

    @Test
    fun `单张异常不影响同批其它图片`() {
        runBlocking {
            val urls = (1..5).map { "https://a.test/p/$it.jpg" }
            val stats = ensureImageBatch(urls, 2) { url ->
                if (url.endsWith("3.jpg")) throw IllegalStateException("坏图") else ImageCacheResult.STORED
            }
            assertEquals(5, stats.total)
            assertEquals(4, stats.stored)
            assertEquals(1, stats.failed)
        }
    }

    @Test
    fun `批量取消会立刻停止派发`() {
        runBlocking {
            val urls = (1..100).map { "https://a.test/p/$it.jpg" }
            val processed = AtomicInteger(0)
            val job = launch {
                ensureImageBatch(urls, 2) {
                    processed.incrementAndGet()
                    delay(5)
                    ImageCacheResult.STORED
                }
            }
            while (processed.get() < 4) {
                delay(1)
            }
            job.cancelAndJoin()
            val done = processed.get()
            assertTrue("取消后不应继续处理剩余图片，实际处理 $done", done < urls.size)
            //取消之后不再派发：给协程一点时间再确认计数没有再涨
            delay(30)
            assertEquals("取消后计数不应再变化", done, processed.get())
        }
    }

    // ---------- 6. 缓存身份 ----------

    @Test
    fun `同一地址的缓存键稳定`() {
        val url = "https://a.test/p/1.jpg"
        assertEquals(MangaImageKeys.imageFileName(url), MangaImageKeys.imageFileName(url))
    }

    @Test
    fun `换源后同一路径不会命中旧源的图片`() {
        runBlocking {
            val store = FakeStore()
            val c = cache(store) { validImage }
            val old = "https://old.test/p/1.jpg"
            val new = "https://new.test/p/1.jpg"
            assertNotEquals(
                "不同域名的绝对地址必须产生不同键",
                MangaImageKeys.imageFileName(old),
                MangaImageKeys.imageFileName(new)
            )
            assertEquals(ImageCacheResult.STORED, c.ensure(old))
            //新源地址不能命中旧源文件，必须重新下载
            assertEquals(ImageCacheResult.STORED, c.ensure(new))
            assertTrue(store.isUsable(old))
            assertTrue(store.isUsable(new))
        }
    }

    @Test
    fun `书目录绑定 bookUrl 换源后目录不同`() {
        val a = Book(bookUrl = "https://a.test/1/", name = "测试漫画", author = "作者")
        val b = Book(bookUrl = "https://b.test/1/", name = "测试漫画", author = "作者")
        assertNotEquals(
            MangaImageKeys.bookFolderName(a),
            MangaImageKeys.bookFolderName(b)
        )
        assertTrue(MangaImageKeys.bookFolderName(a).startsWith("测试漫画"))
    }

    // ---------- 7. 图片地址提取 ----------

    @Test
    fun `懒加载属性优先于占位图`() {
        val content = """<div><img class="lazy" data-src="/p/1.jpg" src="/static/loading.gif"></div>"""
        assertEquals(
            listOf("https://a.test/p/1.jpg"),
            MangaImageExtractor.extract("https://a.test/ch/1", content)
        )
    }

    @Test
    fun `data-original 也可作为懒加载地址`() {
        val content = """<img data-original="/p/2.jpg" src="/static/blank.gif">"""
        assertEquals(
            listOf("https://a.test/p/2.jpg"),
            MangaImageExtractor.extract("https://a.test/ch/1", content)
        )
    }

    @Test
    fun `普通 img 标签与旧实现结果一致`() {
        val content = """<img src="https://cdn.test/p/3.jpg"><img src="/p/4.jpg">"""
        assertEquals(
            listOf("https://cdn.test/p/3.jpg", "https://a.test/p/4.jpg"),
            MangaImageExtractor.extract("https://a.test/ch/1", content)
        )
    }

    @Test
    fun `整章懒加载占位符不会退化成一张图`() {
        val content = buildString {
            for (i in 1..200) {
                append("""<img class="lazy" data-src="/p/$i.jpg" src="/static/loading.gif">""")
            }
        }
        val urls = MangaImageExtractor.extract("https://a.test/ch/1", content)
        assertEquals(200, urls.size)
        assertEquals(200, MangaImageExtractor.collapseConsecutive(urls).size)
    }

    @Test
    fun `相邻重复会被折叠 非相邻重复保留`() {
        assertEquals(
            listOf("a", "b"),
            MangaImageExtractor.collapseConsecutive(listOf("a", "a", "b"))
        )
        assertEquals(
            listOf("a", "b", "a"),
            MangaImageExtractor.collapseConsecutive(listOf("a", "b", "a"))
        )
    }

    // ---------- 8. 容量清理 ----------

    private fun file(name: String, size: Long, time: Long) = ImageFileInfo(name, size, time)

    @Test
    fun `未超上限时不删任何文件`() {
        val files = listOf(file("a", 100, 1), file("b", 100, 2))
        assertTrue(planImageCacheTrim(files, limitBytes = 1000).isEmpty())
    }

    @Test
    fun `超上限时按最久未修改优先删除并留出余量`() {
        val files = listOf(
            file("old", 400, 1),
            file("mid", 400, 2),
            file("new", 400, 3),
        )
        //上限 1000，总量 1200 → 需要降到 800，删掉最老的 400 即可
        assertEquals(listOf("old"), planImageCacheTrim(files, limitBytes = 1000))
    }

    @Test
    fun `上限为零表示不裁剪`() {
        val files = listOf(file("a", 100, 1))
        assertTrue(planImageCacheTrim(files, limitBytes = 0).isEmpty())
    }

    @Test
    fun `单张大图也能被裁剪到余量以内`() {
        val files = listOf(file("huge", 5000, 1), file("small", 10, 2))
        assertEquals(listOf("huge"), planImageCacheTrim(files, limitBytes = 1000))
    }

    @Test
    fun `容量裁剪能识别非图片后缀文件`() {
        val files = listOf(
            file("aaa.mcache", 400, 1),
            file("bbb.mcache", 400, 2),
            file("ccc.mcache", 400, 3),
        )
        assertEquals(listOf("aaa.mcache"), planImageCacheTrim(files, limitBytes = 1000))
    }

    // ---------- 9. 非图片后缀 ----------

    @Test
    fun `新缓存使用非图片后缀且与旧命名共享同一 basename`() {
        val url = "https://a.test/p/1.jpg"
        val newName = MangaImageKeys.imageFileName(url)
        val legacyName = MangaImageKeys.legacyImageFileName(url)
        assertTrue(newName, newName.endsWith(".mcache"))
        assertTrue(legacyName, legacyName.endsWith(".jpg"))
        assertEquals(MangaImageKeys.baseName(url), newName.substringBeforeLast('.'))
        assertEquals(MangaImageKeys.baseName(url), legacyName.substringBeforeLast('.'))
    }

    @Test
    fun `后缀按内容决定 不按 URL 猜`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        val gif = "GIF89a".toByteArray()
        val svg = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"></svg>""".toByteArray()
        assertEquals(MANGA_CACHE_SUFFIX, MangaImageContent.suffixFor(validImage, "jpg"))
        assertEquals(MANGA_CACHE_SUFFIX, MangaImageContent.suffixFor(jpeg, "png"))
        assertEquals(MANGA_CACHE_SUFFIX, MangaImageContent.suffixFor(webp, "gif"))
        assertEquals(MANGA_CACHE_SUFFIX, MangaImageContent.suffixFor(gif, "jpg"))
        //SVG 没有位图编码器，必须保持可被对应解码器识别的后缀
        assertEquals("svg", MangaImageContent.suffixFor(svg, "jpg"))
        //无法识别的内容不猜格式，回退 URL 后缀
        assertEquals("jpg", MangaImageContent.suffixFor("not an image".toByteArray(), "JPG"))
    }

    @Test
    fun `旧后缀缓存仍能被找到 且新旧不会同时算两个缓存`() {
        val base = MangaImageKeys.baseName("https://a.test/p/1.jpg")
        //新后缀优先
        assertEquals(
            "$base.mcache",
            MangaImageContent.resolveExistingCacheName(base, "jpg") { it == "$base.mcache" }
        )
        //只有旧缓存时也能命中（升级不清缓存）
        assertEquals(
            "$base.jpg",
            MangaImageContent.resolveExistingCacheName(base, "jpg") { it == "$base.jpg" }
        )
        //两个都在时取新后缀，避免同一张图两份
        assertEquals(
            "$base.mcache",
            MangaImageContent.resolveExistingCacheName(base, "jpg") {
                it == "$base.jpg" || it == "$base.mcache"
            }
        )
        assertNull(MangaImageContent.resolveExistingCacheName(base, "jpg") { false })
    }

    @Test
    fun `带 EXIF 或 ICC 元数据的图片会被识别`() {
        //裸 JPEG（只有 SOI/EOI）
        assertFalse(
            MangaImageContent.hasMetadataSegments(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            )
        )
        //SOI + APP1(EXIF) + EOI
        val withExif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x08, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        assertTrue(MangaImageContent.hasMetadataSegments(withExif))
        //带填充字节（FF FF E1）的 JPEG 也要能识别出 APP1，否则会漏掉旋转信息
        val withFill = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x08, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        assertTrue(MangaImageContent.hasMetadataSegments(withFill))
        //PNG 里带 iCCP 色彩配置
        assertTrue(
            MangaImageContent.hasMetadataSegments(validImage + "iCCP".toByteArray())
        )
        assertFalse(MangaImageContent.hasMetadataSegments(validImage))
    }

    // ---------- 10. 落盘前压缩（优化） ----------

    private fun cacheWith(
        store: ImageStore,
        optimize: suspend (ByteArray) -> ByteArray,
        fetch: suspend (String) -> ByteArray?,
    ) = PersistentImageCache(
        store = store,
        isValidData = ::isValidImage,
        fetch = fetch,
        optimize = optimize,
    )

    @Test
    fun `只采用更小的压缩结果`() {
        val original = ByteArray(100) { 1 }
        val smaller = ByteArray(50) { 2 }
        val bigger = ByteArray(200) { 3 }
        assertArrayEquals(smaller, MangaImageContent.selectSmallerOrOriginal(original, smaller))
        assertArrayEquals(original, MangaImageContent.selectSmallerOrOriginal(original, bigger))
        assertArrayEquals(original, MangaImageContent.selectSmallerOrOriginal(original, null))
        assertArrayEquals(
            original,
            MangaImageContent.selectSmallerOrOriginal(original, ByteArray(0))
        )
    }

    @Test
    fun `压缩结果被写入缓存`() {
        runBlocking {
            val store = FakeStore()
            val optimized = validImage.copyOf(10)
            val c = cacheWith(store, { optimized }, { validImage })
            assertEquals(ImageCacheResult.STORED, c.ensure("k"))
            assertArrayEquals(optimized, store.files["k"])
        }
    }

    @Test
    fun `命中本地缓存时不会重复转码`() {
        runBlocking {
            val store = FakeStore()
            val optimizeCalls = AtomicInteger(0)
            val c = cacheWith(store, { bytes ->
                optimizeCalls.incrementAndGet()
                bytes
            }, { validImage })
            assertEquals(ImageCacheResult.STORED, c.ensure("k"))
            assertEquals(ImageCacheResult.HIT, c.ensure("k"))
            assertEquals("命中后不应再跑一次编码", 1, optimizeCalls.get())
        }
    }

    @Test
    fun `压缩失败时保留原始内容且仍算成功`() {
        runBlocking {
            val store = FakeStore()
            val c = cacheWith(store, { throw IllegalStateException("编码器挂了") }, { validImage })
            assertEquals(ImageCacheResult.STORED, c.ensure("k"))
            assertArrayEquals(validImage, store.files["k"])
        }
    }

    @Test
    fun `压缩返回空时保留原始内容`() {
        runBlocking {
            val store = FakeStore()
            val c = cacheWith(store, { ByteArray(0) }, { validImage })
            assertEquals(ImageCacheResult.STORED, c.ensure("k"))
            assertArrayEquals(validImage, store.files["k"])
        }
    }

    @Test
    fun `压缩过程被取消时向上传播且不落盘`() {
        runBlocking {
            val store = FakeStore()
            val c = cacheWith(store, { throw CancellationException("取消") }, { validImage })
            try {
                c.ensure("k")
                fail("取消必须向上传播")
            } catch (e: CancellationException) {
                //预期
            }
            assertFalse("取消时不能留下文件", store.files.containsKey("k"))
        }
    }
}
