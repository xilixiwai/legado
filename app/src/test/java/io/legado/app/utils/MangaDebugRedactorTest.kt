package io.legado.app.utils

import org.junit.Assert.*
import org.junit.Test

class MangaDebugRedactorTest {
    @Test fun `headers redact cookie authorization proxy and set cookie`() {
        val names = listOf("Cookie", "Authorization", "Proxy-Authorization", "Set-Cookie")
        val value = MangaDebugRedactor.headers(names.map { it to "private-$it" })
        names.forEach { assertTrue(value.contains("$it: ***")) }
        assertFalse(value.contains("private"))
    }

    @Test fun `URL credentials verify signature and nested URL are hidden`() {
        val url = "https://private-user:private-password@fake.example/a?verify=private-v&token=private-t&signature=private-s&page=2"
        for (text in listOf(MangaDebugRedactor.url(url), MangaDebugRedactor.text("failed at $url"),
            MangaDebugRedactor.headers(listOf("Location" to url)))) {
            assertFalse(text, text.contains("private"))
            assertTrue(text.contains("page=2"))
        }
    }

    @Test fun `nested objects arrays and escaped quoted credentials are hidden`() {
        val input = """{"outer":{"token":"private\"quoted","auth":{"nested":"private-nested"},"cookie":["private-array"]},"message":"denied"}"""
        val output = MangaDebugRedactor.text(input)
        assertFalse(output, output.contains("private"))
        assertTrue(output.contains("denied"))
    }

    @Test fun `ordinary text obvious values and multiword authorization are hidden`() {
        val text = "Cookie: sid=private-cookie\nAuthorization: Bearer private-auth\n" +
            "password=private-pass verify=private-verify authorization=Bearer private-inline token='private spaced value'"
        assertFalse(MangaDebugRedactor.text(text).contains("private"))
    }

    @Test fun `HTML password field is redacted regardless of attribute order`() {
        for (tag in listOf("<input name=\"password\" value=\"private\">", "<input value=\"private\" name=\"token\">")) {
            assertFalse(MangaDebugRedactor.text(tag).contains("private"))
        }
    }

    @Test fun `long nonsensitive body has bounded output and bounded regex work`() {
        val start = System.nanoTime()
        assertEquals(32_000, MangaDebugRedactor.text("a".repeat(100_000)).length)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    @Test fun `secret crossing sample boundary is not exposed`() {
        val body = "x ".repeat(15_995) + "token=" + "private".repeat(8_000)
        assertFalse(MangaDebugRedactor.text(body).contains("private"))
    }

    @Test fun `unknown headers and URL rule options are never exposed`() {
        assertFalse(MangaDebugRedactor.headers(listOf("X-Custom" to "private")).contains("private"))
        assertFalse(MangaDebugRedactor.url("https://fake.example/a,{\"headers\":{\"Cookie\":\"private\"}}").contains("private"))
        assertFalse(MangaDebugRedactor.url("data:image/png;base64,private").contains("private"))
    }

    @Test fun `inline and indented credential headers mask entire value`() {
        for (text in listOf("  Cookie: a=private1; b=private2", "error Cookie: a=private1; b=private2",
            "Proxy-Authorization: Digest username=private1, response=private2")) {
            assertFalse(MangaDebugRedactor.text(text).contains("private"))
        }
    }

    @Test fun `duplicate response URL headers keep distinct nonsecret paths`() {
        val result = MangaDebugRedactor.headers(listOf("Location" to "https://fake.example/a?verify=private",
            "Location" to "https://fake.example/b?verify=private"))
        assertTrue(result.contains("/a?"))
        assertTrue(result.contains("/b?"))
        assertFalse(result.contains("private"))
    }
}
