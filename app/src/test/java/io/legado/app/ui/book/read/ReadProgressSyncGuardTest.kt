package io.legado.app.ui.book.read

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 自动同步守卫:云端数值超前但时间更旧的历史遗留进度不得覆盖本地新位置。
 * 对应真机案例:本地 idx=92(time 较新),云端 idx=94(time 较旧) → 必须跳过。
 */
class ReadProgressSyncGuardTest {

    private fun local(idx: Int, pos: Int = 0, time: Long = 1000L) = Book().apply {
        durChapterIndex = idx
        durChapterPos = pos
        durChapterTime = time
    }

    private fun remote(idx: Int, pos: Int = 0, time: Long) = BookProgress(
        name = "book",
        author = "author",
        durChapterIndex = idx,
        durChapterPos = pos,
        durChapterTime = time,
        durChapterTitle = null
    )

    /** 真机案例:云端章节超前但时间是旧快照 → 不应用 */
    @Test
    fun `stale remote with higher index is rejected`() {
        val local = local(idx = 92, time = 1791186765425L)
        val stale = remote(idx = 94, time = 1791182336707L)
        assertFalse(shouldApplyRemoteProgress(stale, local))
    }

    /** 同章节,云端位置超前但时间更旧 → 不应用 */
    @Test
    fun `stale remote with same index higher pos is rejected`() {
        val local = local(idx = 92, pos = 100, time = 2000L)
        val stale = remote(idx = 92, pos = 500, time = 1000L)
        assertFalse(shouldApplyRemoteProgress(stale, local))
    }

    /** 云端时间与本地相等 → 视为不比本地新,不应用 */
    @Test
    fun `remote with equal time is rejected`() {
        val local = local(idx = 92, time = 2000L)
        val remote = remote(idx = 94, time = 2000L)
        assertFalse(shouldApplyRemoteProgress(remote, local))
    }

    /** 云端章节超前且时间确实更新 → 应用(跨设备正常同步不误杀) */
    @Test
    fun `fresh remote with higher index is applied`() {
        val local = local(idx = 92, time = 1000L)
        val fresh = remote(idx = 94, time = 2000L)
        assertTrue(shouldApplyRemoteProgress(fresh, local))
    }

    /** 同章节位置超前且时间更新 → 应用 */
    @Test
    fun `fresh remote with same index higher pos is applied`() {
        val local = local(idx = 92, pos = 100, time = 1000L)
        val fresh = remote(idx = 92, pos = 500, time = 2000L)
        assertTrue(shouldApplyRemoteProgress(fresh, local))
    }

    /** 旧格式云端数据无时间(durChapterTime=0)→ 保持旧行为,应用 */
    @Test
    fun `legacy remote without time keeps old behavior`() {
        val local = local(idx = 92, time = 2000L)
        val legacy = remote(idx = 94, time = 0L)
        assertTrue(shouldApplyRemoteProgress(legacy, local))
    }

    /** 边界:时间恰为 0 的语义与负值一致,均视为无时间 */
    @Test
    fun `negative time is treated as legacy`() {
        val local = local(idx = 92, time = 2000L)
        val legacy = remote(idx = 94, time = -1L)
        assertTrue(shouldApplyRemoteProgress(legacy, local))
    }

    /** 云端不超前(章节落后)→ 无论时间一律不应用 */
    @Test
    fun `remote behind local is never applied`() {
        val local = local(idx = 94, time = 1000L)
        val behind = remote(idx = 92, time = 9000L)
        assertFalse(shouldApplyRemoteProgress(behind, local))
    }

    /** 云端章节相同且位置相同 → 不应用(调用方已有相等早退,此处兜底) */
    @Test
    fun `identical remote is not applied`() {
        val local = local(idx = 92, pos = 100, time = 1000L)
        val same = remote(idx = 92, pos = 100, time = 9000L)
        assertFalse(shouldApplyRemoteProgress(same, local))
    }
}
