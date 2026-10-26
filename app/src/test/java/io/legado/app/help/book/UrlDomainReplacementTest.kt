package io.legado.app.help.book

import io.legado.app.help.book.UrlDomainReplacement.InvalidReason
import io.legado.app.help.book.UrlDomainReplacement.NormalizeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「批量替换域名」纯逻辑层的单元测试（正则匹配版）。
 *
 * 覆盖点：
 * 1. 旧域名支持正则，但**只匹配解析出的完整主机名**，不会扫整条 URL；
 * 2. 裸主机名按字面量处理，`old.com` 不会匹配 `oldXcom`；
 * 3. 端口、userinfo、scheme、路径、查询、片段、规则参数块全部原样保留；
 * 4. 新域名是纯字面量，`$1` / `\1` 不会被当成替换模板；
 * 5. 危险输入（空、非法正则、能匹配空串的正则、非法新域名）一律拒绝；
 * 6. 幂等：已经是新域名时返回 null，重复执行不会重复改。
 *
 * 数据库层的真实 SQL 行为由 `DomainReplaceSqlTest` 用真实 SQLite 验证。
 */
class UrlDomainReplacementTest {

    // ------------------------------------------------------------------ 工具

    private fun rule(old: String, new: String): UrlDomainReplacement.DomainRule {
        return when (val r = UrlDomainReplacement.normalize(old, new)) {
            is NormalizeResult.Success -> r.rule
            is NormalizeResult.Failure -> throw AssertionError("规则应合法，实际失败：${r.reason}")
        }
    }

    private fun failureReason(old: String, new: String): InvalidReason {
        return when (val r = UrlDomainReplacement.normalize(old, new)) {
            is NormalizeResult.Success -> throw AssertionError("规则应被拒绝，实际通过：${r.rule}")
            is NormalizeResult.Failure -> r.reason
        }
    }

    // ------------------------------------------------------------------ 输入校验

    @Test
    fun `空旧域名被拒绝`() {
        assertEquals(InvalidReason.EMPTY_OLD, failureReason("", "new.com"))
        assertEquals(InvalidReason.EMPTY_OLD, failureReason("   ", "new.com"))
    }

    @Test
    fun `空新域名被拒绝`() {
        assertEquals(InvalidReason.EMPTY_NEW, failureReason("old.com", ""))
        assertEquals(InvalidReason.EMPTY_NEW, failureReason("old.com", "  "))
    }

    @Test
    fun `非法正则被拒绝`() {
        assertEquals(InvalidReason.INVALID_OLD_REGEX, failureReason("[", "new.com"))
        assertEquals(InvalidReason.INVALID_OLD_REGEX, failureReason("a(", "new.com"))
        assertEquals(InvalidReason.INVALID_OLD_REGEX, failureReason("\\", "new.com"))
    }

    @Test
    fun `能匹配空串的正则被拒绝`() {
        assertEquals(InvalidReason.OLD_REGEX_TOO_BROAD, failureReason(".*", "new.com"))
        assertEquals(InvalidReason.OLD_REGEX_TOO_BROAD, failureReason("a*", "new.com"))
        assertEquals(InvalidReason.OLD_REGEX_TOO_BROAD, failureReason("(foo)?", "new.com"))
    }

    @Test
    fun `新域名带端口被拒绝`() {
        assertEquals(InvalidReason.INVALID_NEW, failureReason("old.com", "new.com:8080"))
    }

    @Test
    fun `新域名带路径查询片段被拒绝`() {
        assertEquals(InvalidReason.INVALID_NEW, failureReason("old.com", "new.com/a"))
        assertEquals(InvalidReason.INVALID_NEW, failureReason("old.com", "new.com?a=1"))
        assertEquals(InvalidReason.INVALID_NEW, failureReason("old.com", "new.com#f"))
    }

    @Test
    fun `新域名带 userinfo 被拒绝`() {
        assertEquals(InvalidReason.INVALID_NEW, failureReason("old.com", "u:p@new.com"))
    }

    @Test
    fun `新域名含正则模板字符被拒绝而不是当成捕获组`() {
        // $1 / \1 这类替换模板不是合法主机名字符，直接拒绝，
        // 绝不做捕获组展开——否则用户会得到一个自己没输入过的域名。
        assertEquals(
            InvalidReason.INVALID_NEW,
            failureReason("^gpt\\d+\\.example\\.com$", "\$1.example.com")
        )
        assertEquals(
            InvalidReason.INVALID_NEW,
            failureReason("^gpt\\d+\\.example\\.com$", "\\1.example.com")
        )
        assertEquals(InvalidReason.INVALID_NEW, failureReason("old.com", "new.com\$"))
    }

    @Test
    fun `新域名允许带 scheme 与尾部斜杠`() {
        assertEquals("new.com", UrlDomainReplacement.parseNewDomain("new.com"))
        assertEquals("new.com", UrlDomainReplacement.parseNewDomain("https://new.com"))
        assertEquals("new.com", UrlDomainReplacement.parseNewDomain("http://new.com/"))
        assertEquals("new.com", UrlDomainReplacement.parseNewDomain("  NEW.COM  "))
        assertEquals("new.com", UrlDomainReplacement.parseNewDomain("new.com."))
    }

    @Test
    fun `新域名非法主机名被拒绝`() {
        assertNull(UrlDomainReplacement.parseNewDomain("-bad.com"))
        assertNull(UrlDomainReplacement.parseNewDomain("bad-.com"))
        assertNull(UrlDomainReplacement.parseNewDomain("new .com"))
        assertNull(UrlDomainReplacement.parseNewDomain("new_com"))
        assertNull(UrlDomainReplacement.parseNewDomain("ftp://new.com"))
    }

    @Test
    fun `字面量旧域名与目标域名相同时被拒绝`() {
        assertEquals(InvalidReason.SAME_AS_NEW_DOMAIN, failureReason("old.com", "old.com"))
        assertEquals(InvalidReason.SAME_AS_NEW_DOMAIN, failureReason("OLD.COM", "https://old.com/"))
    }

    // ------------------------------------------------------------------ 裸主机名按字面量处理

    @Test
    fun `裸主机名被转义为字面量`() {
        val r = rule("old.com", "new.com")
        assertTrue(r.literal)
        assertEquals(Regex.escape("old.com"), r.pattern)
        assertTrue(r.matchesHost("old.com"))
        // "." 不是通配符
        assertFalse(r.matchesHost("oldXcom"))
        assertFalse(r.matchesHost("oldacom"))
    }

    @Test
    fun `裸主机名不匹配子域名`() {
        val r = rule("old.com", "new.com")
        assertFalse(r.matchesHost("m.old.com"))
        assertFalse(r.matchesHost("old.com.evil.com"))
    }

    @Test
    fun `主机名大小写与尾部点被归一`() {
        val r = rule("old.com", "new.com")
        assertTrue(r.matchesHost("OLD.COM"))
        assertTrue(r.matchesHost("Old.Com"))
        assertTrue(r.matchesHost("old.com."))
    }

    // ------------------------------------------------------------------ 正则匹配

    @Test
    fun `正则匹配多个历史域名`() {
        val r = rule("^gpt\\d+\\.example\\.com$", "gpt.example.org")
        assertFalse(r.literal)
        assertTrue(r.matchesHost("gpt1.example.com"))
        assertTrue(r.matchesHost("gpt3.example.com"))
        assertTrue(r.matchesHost("gpt12.example.com"))
        // 边界：不匹配
        assertFalse(r.matchesHost("gpt.example.com"))
        assertFalse(r.matchesHost("gptx.example.com"))
        assertFalse(r.matchesHost("gpt1.example.com.evil.com"))
        assertFalse(r.matchesHost("a.gpt1.example.com"))
    }

    @Test
    fun `任务用例_gpt历史域名整体替换为新域名`() {
        // 真机排查用例：旧域名正则 ^gpt\d+\.example\.com$，
        // 库中 https://gpt123.example.com/book/1 必须在预览命中并替换为 gpt2.example.com
        val r = rule("^gpt\\d+\\.example\\.com$", "gpt2.example.com")
        assertFalse(r.literal)
        // 多种数字后缀统一命中
        assertTrue(r.matchesHost("gpt123.example.com"))
        assertTrue(r.matchesHost("gpt7.example.com"))
        assertTrue(r.matchesHost("gpt1000.example.com"))
        assertEquals(
            "https://gpt2.example.com/book/1",
            UrlDomainReplacement.replaceDomain("https://gpt123.example.com/book/1", r)
        )
        // 不匹配的域名保持不变
        assertNull(UrlDomainReplacement.replaceDomain("https://gpt.example.com/book/1", r))
        assertNull(UrlDomainReplacement.replaceDomain("https://other.example.com/book/1", r))
    }

    @Test
    fun `正则大小写不敏感`() {
        // 主机名先转小写再匹配，模式侧按域名语义同样忽略大小写：
        // 输入大写正则不能出现「永远零匹配」
        val r = rule("^GPT\\d+\\.EXAMPLE\\.COM$", "gpt2.example.com")
        assertTrue(r.matchesHost("gpt123.example.com"))
        assertTrue(r.matchesHost("GPT123.EXAMPLE.COM"))
    }

    @Test
    fun `正则支持重复次数与字符组元字符`() {
        val r = rule("^gpt\\d{2,3}\\.example\\.com$", "gpt2.example.com")
        assertTrue(r.matchesHost("gpt12.example.com"))
        assertTrue(r.matchesHost("gpt123.example.com"))
        assertFalse(r.matchesHost("gpt1.example.com"))
        val r2 = rule("^(img|static)\\.[a-z]+\\.com$", "new.com")
        assertTrue(r2.matchesHost("img.example.com"))
        assertFalse(r2.matchesHost("img.example.org"))
    }

    @Test
    fun `正则只匹配完整主机名`() {
        val r = rule("^m\\.old\\.com$", "new.com")
        assertTrue(r.matchesHost("m.old.com"))
        assertFalse(r.matchesHost("m.old.com.cn"))
        assertFalse(r.matchesHost("xm.old.com"))
    }

    @Test
    fun `正则可用交替匹配多个域名`() {
        val r = rule("^(m|www|api)\\.old\\.com$", "new.com")
        assertTrue(r.matchesHost("m.old.com"))
        assertTrue(r.matchesHost("www.old.com"))
        assertTrue(r.matchesHost("api.old.com"))
        assertFalse(r.matchesHost("img.old.com"))
    }

    // ------------------------------------------------------------------ URL 替换：scheme / 端口 / 路径 / 查询 / 片段

    @Test
    fun `替换主机名保留协议与路径`() {
        val r = rule("old.com", "new.com")
        assertEquals(
            "http://new.com/a/b.html",
            UrlDomainReplacement.replaceDomain("http://old.com/a/b.html", r)
        )
        assertEquals(
            "https://new.com/a/b.html",
            UrlDomainReplacement.replaceDomain("https://old.com/a/b.html", r)
        )
    }

    @Test
    fun `协议不自动升级`() {
        val r = rule("old.com", "new.com")
        assertEquals("http://new.com/x", UrlDomainReplacement.replaceDomain("http://old.com/x", r))
    }

    @Test
    fun `端口原样保留`() {
        val r = rule("old.com", "new.com")
        assertEquals(
            "http://new.com:8080/x",
            UrlDomainReplacement.replaceDomain("http://old.com:8080/x", r)
        )
        assertEquals(
            "https://new.com:443/x",
            UrlDomainReplacement.replaceDomain("https://old.com:443/x", r)
        )
    }

    @Test
    fun `带端口的旧域名替换后不会拼出坏 URL`() {
        // 历史 bug 回归：曾经拼出 http://m.old.com:8080m.new.com:8080/x
        val r = rule("^m\\.old\\.com$", "m.new.com")
        val result = UrlDomainReplacement.replaceDomain("http://m.old.com:8080/x", r)
        assertEquals("http://m.new.com:8080/x", result)
        assertFalse(result!!.contains("m.old.com"))
    }

    @Test
    fun `userinfo 原样保留`() {
        val r = rule("old.com", "new.com")
        assertEquals(
            "http://user:pw@new.com:8080/x",
            UrlDomainReplacement.replaceDomain("http://user:pw@old.com:8080/x", r)
        )
    }

    @Test
    fun `查询与片段原样保留`() {
        val r = rule("old.com", "new.com")
        assertEquals(
            "http://new.com/x?a=1&b=2#frag",
            UrlDomainReplacement.replaceDomain("http://old.com/x?a=1&b=2#frag", r)
        )
        assertEquals(
            "http://new.com?a=1",
            UrlDomainReplacement.replaceDomain("http://old.com?a=1", r)
        )
        assertEquals(
            "http://new.com#frag",
            UrlDomainReplacement.replaceDomain("http://old.com#frag", r)
        )
    }

    @Test
    fun `无路径的纯域名也能替换`() {
        val r = rule("old.com", "new.com")
        assertEquals("http://new.com", UrlDomainReplacement.replaceDomain("http://old.com", r))
    }

    @Test
    fun `规则参数块原样保留`() {
        val r = rule("old.com", "new.com")
        assertEquals(
            "http://new.com/api,{\"method\":\"POST\"}",
            UrlDomainReplacement.replaceDomain("http://old.com/api,{\"method\":\"POST\"}", r)
        )
    }

    @Test
    fun `路径里的域名文本不会被误改`() {
        // 匹配的是主机名，不是整条 URL
        val r = rule("old.com", "new.com")
        assertNull(
            UrlDomainReplacement.replaceDomain("http://cdn.partner.com/old.com/x.html", r)
        )
    }

    @Test
    fun `不相关域名保持不变`() {
        val r = rule("old.com", "new.com")
        assertNull(UrlDomainReplacement.replaceDomain("http://other.com/x", r))
        assertNull(UrlDomainReplacement.replaceDomain("https://cdn.partner.com/a.jpg", r))
    }

    @Test
    fun `已经是新域名时返回 null`() {
        val r = rule("old.com", "new.com")
        assertNull(UrlDomainReplacement.replaceDomain("http://new.com/x", r))
    }

    @Test
    fun `相对地址与非 http 地址不处理`() {
        val r = rule("old.com", "new.com")
        assertNull(UrlDomainReplacement.replaceDomain("/x/y.html", r))
        assertNull(UrlDomainReplacement.replaceDomain("", r))
        assertNull(UrlDomainReplacement.replaceDomain("ftp://old.com/x", r))
    }

    @Test
    fun `IPv6 字面量不参与域名替换`() {
        val r = rule("old.com", "new.com")
        assertNull(UrlDomainReplacement.replaceDomain("http://[::1]:8080/x", r))
    }

    // ------------------------------------------------------------------ 书籍两处地址

    @Test
    fun `书籍两处地址都命中时都替换`() {
        val r = rule("old.com", "new.com")
        val urls = UrlDomainReplacement.replaceBookUrls(
            "http://old.com/book/1",
            "http://old.com/book/1/toc",
            r
        )
        assertNotNull(urls)
        assertEquals("http://new.com/book/1", urls!!.bookUrl)
        assertEquals("http://new.com/book/1/toc", urls.tocUrl)
    }

    @Test
    fun `bookUrl 已是新域名时 tocUrl 仍继续替换`() {
        // 部分完成状态必须能被修复，不能整本书跳过
        val r = rule("old.com", "new.com")
        val urls = UrlDomainReplacement.replaceBookUrls(
            "http://new.com/book/1",
            "http://old.com/book/1/toc",
            r
        )
        assertNotNull(urls)
        assertNull(urls!!.bookUrl)
        assertEquals("http://new.com/book/1/toc", urls.tocUrl)
    }

    @Test
    fun `tocUrl 已是新域名时 bookUrl 仍继续替换`() {
        val r = rule("old.com", "new.com")
        val urls = UrlDomainReplacement.replaceBookUrls(
            "http://old.com/book/1",
            "http://new.com/book/1/toc",
            r
        )
        assertNotNull(urls)
        assertEquals("http://new.com/book/1", urls!!.bookUrl)
        assertNull(urls.tocUrl)
    }

    @Test
    fun `两处都未命中时返回 null`() {
        val r = rule("old.com", "new.com")
        assertNull(
            UrlDomainReplacement.replaceBookUrls("http://new.com/b", "http://new.com/b/toc", r)
        )
    }

    @Test
    fun `tocUrl 为空时不影响 bookUrl 替换`() {
        val r = rule("old.com", "new.com")
        val urls = UrlDomainReplacement.replaceBookUrls("http://old.com/b", "", r)
        assertNotNull(urls)
        assertEquals("http://new.com/b", urls!!.bookUrl)
        assertNull(urls.tocUrl)
    }

    // ------------------------------------------------------------------ 拆解辅助

    @Test
    fun `拆解规则参数块`() {
        val (body, option) =
            UrlDomainReplacement.splitUrlOption("https://a.com/b,{\"method\":\"POST\"}")
        assertEquals("https://a.com/b", body)
        assertEquals(",{\"method\":\"POST\"}", option)

        val (body2, option2) = UrlDomainReplacement.splitUrlOption("https://a.com/b")
        assertEquals("https://a.com/b", body2)
        assertEquals("", option2)
    }

    @Test
    fun `取主机名`() {
        assertEquals("old.com", UrlDomainReplacement.hostOf("http://old.com/x"))
        assertEquals("old.com", UrlDomainReplacement.hostOf("http://user:pw@old.com:8080/x"))
        assertEquals("old.com", UrlDomainReplacement.hostOf("http://OLD.com./x"))
        assertNull(UrlDomainReplacement.hostOf("/relative"))
        assertNull(UrlDomainReplacement.hostOf(""))
        assertEquals("old.com", UrlDomainReplacement.hostOfAuthority("user:pw@old.com:8080"))
    }

    @Test
    fun `判断绝对 http 地址`() {
        assertTrue(UrlDomainReplacement.isAbsoluteHttpUrl("http://a.com/x"))
        assertTrue(UrlDomainReplacement.isAbsoluteHttpUrl("https://a.com"))
        assertTrue(
            UrlDomainReplacement.isAbsoluteHttpUrl("https://a.com/x,{\"method\":\"POST\"}")
        )
        assertFalse(UrlDomainReplacement.isAbsoluteHttpUrl("/x"))
        assertFalse(UrlDomainReplacement.isAbsoluteHttpUrl(""))
        assertFalse(UrlDomainReplacement.isAbsoluteHttpUrl("ftp://a.com/x"))
    }
}
