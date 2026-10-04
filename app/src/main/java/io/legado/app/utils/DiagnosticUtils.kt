package io.legado.app.utils

/**
 * 诊断日志辅助(纯函数,无副作用)。
 *
 * 本轮为"诊断级可观测性增强"专用:
 * - 脱敏:避免把 URL 中的 query / fragment / userinfo(可能含 token、sign、cookie、密码)
 *   写进日志;
 * - 统一耗时 / 异常类型 / 是否超时 的格式,便于在日志里区分
 *   "外部请求慢或失败" 与 "App 内部阻塞或超时"。
 *
 * 约束:本文件不参与任何业务判定,不改变任何原有行为。
 */
object DiagnosticUtils {

    /**
     * 去掉 fragment、query 与 userinfo,只保留 scheme://host/path。
     *
     * 目的:日志足以定位到目标站点与路径,但不泄漏 token、sign、cookie、密码等查询参数。
     */
    fun sanitizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val noFragment = url.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val schemeIdx = noQuery.indexOf("://")
        if (schemeIdx < 0) return noQuery
        val scheme = noQuery.substring(0, schemeIdx + 3)
        val rest = noQuery.substring(schemeIdx + 3)
        val atIdx = rest.lastIndexOf('@')
        return if (atIdx >= 0) scheme + rest.substring(atIdx + 1) else noQuery
    }

    /** 耗时(毫秒),恒为非负值,避免时钟回拨出现负数。 */
    fun elapsedMs(start: Long, end: Long = System.currentTimeMillis()): Long =
        if (end >= start) end - start else 0L

    /** 异常类型简称,便于日志里一眼区分异常种类。 */
    fun exceptionType(t: Throwable?): String {
        if (t == null) return "none"
        val simple = t.javaClass.simpleName
        return if (simple.isNullOrBlank()) t.javaClass.name else simple
    }

    /**
     * 是否为超时类异常(区分"外部请求慢"与"App 内部超时/阻塞"的关键字段)。
     * 会沿 cause 链向上查找若干层。
     */
    fun isTimeout(t: Throwable?): Boolean {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 8) {
            val name = e.javaClass.name
            if (name.contains("Timeout") || name.contains("InterruptedIOException")) {
                return true
            }
            e = e.cause
            depth++
        }
        return false
    }
}
