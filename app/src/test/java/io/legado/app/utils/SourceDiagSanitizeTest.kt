package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [诊断] URL / 规则参数脱敏的纯单元测试。
 *
 * 必须覆盖的场景（对应审批要求）：
 * 普通网址、查询参数与片段、userinfo 凭据、`,{...}` 规则参数块、
 * Cookie / Authorization 一类敏感字段、畸形或无法解析的输入。
 *
 * 核心约束：**无法可靠脱敏时输出固定标记 [SourceDiag.REDACTED]，绝不回退成原始 URL。**
 */
class SourceDiagSanitizeTest {

    @Test
    fun `普通网址只保留协议与主机`() {
        val masked = SourceDiag.maskUrl("https://www.example.com/book/123.html")
        assertTrue(masked, masked.startsWith("https://www.example.com#fp="))
        assertFalse(masked, masked.contains("/book/"))
        assertFalse(masked, masked.contains(".html"))
    }

    @Test
    fun `查询参数与片段不落盘`() {
        val masked = SourceDiag.maskUrl(
            "https://a.com/search?token=SECRET&sign=ABCDEF&q=%E4%B9%A6#fragment"
        )
        assertTrue(masked, masked.startsWith("https://a.com#fp="))
        assertFalse(masked, masked.contains("SECRET"))
        assertFalse(masked, masked.contains("token"))
        assertFalse(masked, masked.contains("sign"))
        assertFalse(masked, masked.contains("fragment"))
    }

    @Test
    fun `userinfo 凭据不落盘`() {
        val masked = SourceDiag.maskUrl("https://user:pass@a.com/private/path")
        assertEquals("https://a.com", masked.substringBefore("#fp="))
        assertFalse(masked, masked.contains("user"))
        assertFalse(masked, masked.contains("pass"))
        assertFalse(masked, masked.contains("/private/path"))
    }

    @Test
    fun `规则参数块整体不落盘`() {
        val masked = SourceDiag.maskUrl(
            "https://a.com/api,{\"method\":\"POST\",\"body\":\"token=SECRET\",\"charset\":\"gbk\"}"
        )
        assertEquals("https://a.com", masked.substringBefore("#fp="))
        assertFalse(masked, masked.contains("{"))
        assertFalse(masked, masked.contains("method"))
        assertFalse(masked, masked.contains("charset"))
        assertFalse(masked, masked.contains("SECRET"))
    }

    @Test
    fun `Cookie 与 Authorization 一类敏感字段不落盘`() {
        val masked = SourceDiag.maskUrl(
            "https://a.com/list, { \"headers\": { \"Cookie\": \"sid=SECRET\", " +
                    "\"Authorization\": \"Bearer TOKENVALUE\" } }"
        )
        assertEquals("https://a.com", masked.substringBefore("#fp="))
        assertFalse(masked, masked.contains("Cookie"))
        assertFalse(masked, masked.contains("sid="))
        assertFalse(masked, masked.contains("Authorization"))
        assertFalse(masked, masked.contains("Bearer"))
        assertFalse(masked, masked.contains("SECRET"))
    }

    @Test
    fun `先切分规则参数块再脱敏是必要的`() {
        val url = "https://a.com/api,{\"method\":\"POST\",\"body\":\"token=SECRET\"}"
        // 既有 sanitizeUrl 只处理 fragment / query / userinfo，不会去掉 `,{...}` 参数块
        assertTrue(DiagnosticUtils.sanitizeUrl(url).contains("SECRET"))
        // 诊断通道必须先 splitUrlOption 再脱敏，所以不会残留
        assertFalse(SourceDiag.maskUrl(url).contains("SECRET"))
    }

    @Test
    fun `畸形输入输出固定隐藏标记`() {
        assertEquals(SourceDiag.REDACTED, SourceDiag.maskUrl("not a url"))
        assertEquals(SourceDiag.REDACTED, SourceDiag.maskUrl("http://"))
        assertEquals(SourceDiag.REDACTED, SourceDiag.maskUrl("https:///path/only"))
        assertEquals(SourceDiag.REDACTED, SourceDiag.maskUrl("//a.com/x"))
        assertEquals(SourceDiag.REDACTED, SourceDiag.maskUrl("/relative/path"))
    }

    @Test
    fun `非 http 协议不输出原文`() {
        val masked = SourceDiag.maskUrl("ftp://a.com/x")
        assertEquals(SourceDiag.REDACTED, masked)
        assertFalse(masked, masked.contains("a.com"))
    }

    @Test
    fun `空值输出占位而不是原文`() {
        assertEquals(SourceDiag.EMPTY, SourceDiag.maskUrl(null))
        assertEquals(SourceDiag.EMPTY, SourceDiag.maskUrl(""))
        assertEquals(SourceDiag.EMPTY, SourceDiag.maskUrl("   "))
    }

    @Test
    fun `端口保留且不带凭据`() {
        val masked = SourceDiag.maskUrl("http://user:pw@a.com:8080/x")
        assertTrue(masked, masked.startsWith("http://a.com:8080#fp="))
        assertFalse(masked, masked.contains("user"))
        assertFalse(masked, masked.contains("pw"))
    }

    @Test
    fun `IPv6 主机名不会被端口解析破坏`() {
        val masked = SourceDiag.maskUrl("http://[::1]:8080/x")
        assertTrue(masked, masked.startsWith("http://[::1]:8080#fp="))
    }

    @Test
    fun `指纹稳定且不可逆`() {
        val a = SourceDiag.fingerprint("https://a.com/book/1")
        val b = SourceDiag.fingerprint("https://a.com/book/1")
        val c = SourceDiag.fingerprint("https://a.com/book/2")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(16, a.length)
        assertFalse(a, a.contains("a.com"))
        assertEquals(SourceDiag.EMPTY, SourceDiag.fingerprint(null))
    }

    @Test
    fun `脱敏结果里不含原始地址`() {
        val cases = listOf(
            "https://a.com/book/123.html",
            "https://a.com/p?token=SECRET",
            "https://user:pass@a.com/p",
            "https://a.com/p,{\"method\":\"POST\"}"
        )
        cases.forEach { url ->
            val masked = SourceDiag.maskUrl(url)
            assertFalse("泄漏原始地址: $masked", masked.contains(url))
            assertTrue("缺少指纹: $masked", masked.contains("#fp="))
        }
    }
}
