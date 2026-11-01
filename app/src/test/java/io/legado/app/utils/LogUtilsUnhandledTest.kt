package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.ActivelyCancelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [logIfUnhandled] / [shouldLogAsUnhandled] 判定回归（P0-2）：
 * - 正常取消（取消、超时、主动取消）不作为未处理异常记录；
 * - 未挂 onError 的真实异常才记录，挂了 onError 的不重复记录；
 * - Debug 构建保持原有行为：只走 printOnDebug 堆栈输出，不产生 AppLog 记录。
 */
class LogUtilsUnhandledTest {

    @Test
    fun 正常取消不记录() {
        val e = CancellationException("job cancelled")
        assertFalse(e.shouldLogAsUnhandled(hasErrorCallback = false))
    }

    @Test
    fun 超时取消不记录() {
        val e = TimeoutCancellationException("timeout")
        assertFalse(e.shouldLogAsUnhandled(hasErrorCallback = false))
    }

    @Test
    fun 主动取消不记录() {
        val e = ActivelyCancelException()
        assertFalse(e.shouldLogAsUnhandled(hasErrorCallback = false))
    }

    @Test
    fun 无onError的真实异常需要记录() {
        val e = IOException("network broken")
        assertTrue(e.shouldLogAsUnhandled(hasErrorCallback = false))
    }

    @Test
    fun 已挂onError的异常不重复记录() {
        val e = IOException("network broken")
        assertFalse(e.shouldLogAsUnhandled(hasErrorCallback = true))
    }

    @Test
    fun debug构建下logIfUnhandled不产生AppLog记录且不抛() {
        val before = AppLog.logs.size
        val e = IOException("unhandled in debug build")
        // 测试构建为 Debug：logIfUnhandled 应保持原有行为（早退，仅 printOnDebug 生效），
        // 不向 AppLog 追加记录，也不抛出任何异常
        e.logIfUnhandled(hasErrorCallback = false)
        e.logIfUnhandled(hasErrorCallback = true)
        assertEquals("Debug 构建下不应新增 AppLog 记录", before, AppLog.logs.size)
    }
}
