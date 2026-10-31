package io.legado.app.model

import io.legado.app.data.entities.Book
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 阶段 0 的既有行为基线，不代表未来处理记录、解密或 Android 文件提交已经实现。
 * 只使用内存存储；字节可用性是假实现，不声称验证真实图片解码。
 * 阶段 1 增补纯身份选择测试；实际执行记录见 PROJECT_STATE.md。
 */
class MangaCacheLegacyCompatibilityTest {

    @Test
    fun `没有处理记录的可用缓存命中不执行下载校验优化或写入`() = runBlocking {
        val src = "https://cdn.test/page.png"
        val original = byteArrayOf(1, 2, 3)
        val store = MemoryStore().apply { files[src] = original.copyOf() }
        val cache = PersistentImageCache(
            store = store,
            fetch = { throw AssertionError("有效旧缓存不能触发下载或解密") },
            isValidData = { throw AssertionError("命中不能进入网络结果校验") },
            optimize = { throw AssertionError("有效旧缓存不能重新压缩") },
        )

        assertEquals(ImageCacheResult.HIT, cache.ensure(src))
        assertEquals(0, store.writes)
        assertEquals(0, store.deletes)
        assertArrayEquals(original, store.files.getValue(src))
    }

    @Test
    fun `跨书同URL即使共享锁也分别保存各自返回内容`() = runBlocking {
        val src = "https://cdn.test/page.png"
        val firstBook = Book(bookUrl = "https://books.test/one", name = "同名漫画")
        val secondBook = Book(bookUrl = "https://books.test/two", name = "同名漫画")
        assertNotEquals(
            MangaImageKeys.bookFolderName(firstBook),
            MangaImageKeys.bookFolderName(secondBook),
        )
        // 两个存储分别代表 BookImageStore 的书目录边界，不代替 Android 存储集成测试。
        val firstStore = MemoryStore()
        val secondStore = MemoryStore()
        val locks = ConcurrentHashMap<String, Mutex>()
        val first = PersistentImageCache(
            store = firstStore,
            fetch = { byteArrayOf(1) },
            isValidData = { it.isNotEmpty() },
            locks = locks,
        )
        val second = PersistentImageCache(
            store = secondStore,
            fetch = { byteArrayOf(2) },
            isValidData = { it.isNotEmpty() },
            locks = locks,
        )

        assertEquals(ImageCacheResult.STORED, first.ensure(src))
        assertEquals(ImageCacheResult.STORED, second.ensure(src))
        assertEquals(ImageCacheResult.HIT, first.ensure(src))
        assertArrayEquals(byteArrayOf(1), firstStore.files.getValue(src))
        assertArrayEquals(byteArrayOf(2), secondStore.files.getValue(src))
    }

    @Test
    fun `旧定位保留查询参数顺序token及完整请求选项`() {
        val original = "https://cdn.test/page.png?a=1&token=one"
        val inputs = listOf(
            original,
            "https://cdn.test/page.png?a=1&token=two",
            "https://cdn.test/page.png?token=one&a=1",
            original + ",{\"headers\":{\"Referer\":\"https://one.test/\"}}",
            original + ",{\"headers\":{\"Referer\":\"https://two.test/\"}}",
        )

        assertEquals(inputs.size, inputs.map { MangaImageKeys.baseName(it) }.toSet().size)
        // 外部 Cookie/Header 不在 src 中的情况，由阶段 1 的新资源身份测试覆盖。
    }

    @Test
    fun `只有旧大写后缀文件时查找不要求改名或补建新文件`() {
        val base = MangaImageKeys.baseName("https://cdn.test/page.PNG")
        val existing = setOf("$base.PNG")
        assertEquals(
            "$base.PNG",
            MangaImageContent.resolveExistingCacheName(base, "PNG", existing::contains),
        )
        assertNull(
            MangaImageContent.resolveExistingCacheName(base, "PNG") { false },
        )
    }

    @Test
    fun `新旧缓存同时存在时精确新身份优先`() {
        val resource = imageResource()
        val record = ImageProcessingRecord(resource, imageDigest, 10, ImageDecodeState.NONE)
        val result = selectImageCache(resource, record, imageDigest, 10, true,
            LegacyImageLocator("book", "https://cdn.test/a"), true)
        assertEquals(ImageCacheSelection.Exact(record), result)
    }

    @Test
    fun `新记录不存在或不匹配时旧缓存仅为未知历史状态`() {
        val resource = imageResource()
        val legacy = LegacyImageLocator("book", "https://cdn.test/a")
        val records = listOf(null,
            ImageProcessingRecord(imageResource(book = "other"), imageDigest, 10, ImageDecodeState.NONE))
        records.forEach { record ->
            val result = selectImageCache(resource, record, imageDigest, 10, true, legacy, true)
            assertTrue(result is ImageCacheSelection.Legacy)
            result as ImageCacheSelection.Legacy
            assertEquals(ImageDecodeState.UNKNOWN, result.decodeState)
            assertFalse(result.mayAutomaticallyDecode)
            assertFalse(result.mayAutomaticallyDelete)
            assertFalse(result.mayAutomaticallyReload)
            assertFalse(result.mayAutomaticallyConfirm)
        }
    }

    @Test
    fun `新缓存字节摘要长度或可读性不符不能确认命中`() {
        val resource = imageResource()
        val record = ImageProcessingRecord(resource, imageDigest, 10, ImageDecodeState.NONE)
        assertEquals(ImageCacheSelection.Missing,
            selectImageCache(resource, record, imageDigest, 11, true, null, false))
        assertEquals(ImageCacheSelection.Missing,
            selectImageCache(resource, record, ImageFingerprint("b".repeat(64)), 10, true, null, false))
        assertEquals(ImageCacheSelection.Missing,
            selectImageCache(resource, record, imageDigest, 10, false, null, false))
    }

    @Test
    fun `不可读未知旧文件也不降为缺失或允许自动重下载`() {
        val result = selectImageCache(imageResource(), null, null, null, false,
            LegacyImageLocator("book", "https://cdn.test/a"), false)
        assertTrue(result is ImageCacheSelection.Legacy)
        result as ImageCacheSelection.Legacy
        assertFalse(result.readable)
        assertFalse(result.mayAutomaticallyReload)
        assertFalse(result.mayAutomaticallyDelete)
        assertEquals(ImageDecodeState.UNKNOWN, result.decodeState)
        assertEquals(ImageCacheHitOrigin.LEGACY_UNKNOWN, ImageOutcome.CacheHit(imageResource()).origin)
    }

    private class MemoryStore : ImageStore {
        val files = mutableMapOf<String, ByteArray>()
        var writes = 0
        var deletes = 0

        override fun exists(key: String): Boolean = files.containsKey(key)

        override fun isUsable(key: String): Boolean = files[key]?.isNotEmpty() == true

        override fun delete(key: String) {
            deletes++
            files.remove(key)
        }

        override fun writeAtomic(key: String, bytes: ByteArray): Boolean {
            writes++
            files[key] = bytes.copyOf()
            return true
        }
    }
}
