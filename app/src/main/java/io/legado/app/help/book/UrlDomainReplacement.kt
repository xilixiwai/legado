package io.legado.app.help.book

import java.util.regex.PatternSyntaxException

/**
 * 「批量替换域名」的纯逻辑核心。
 *
 * 规则要点：
 * 1. **旧域名支持正则**：正则只对「解析出来的完整主机名」做匹配（`Regex.matches`，整串匹配），
 *    绝不拿正则去扫整条 URL，因此不会误伤路径 / 查询 / 片段里恰好出现的域名文本。
 * 2. 输入若是一个裸主机名（只含 `[a-z0-9._-]`），自动按**字面量**转义，
 *    避免 `old.com` 里的 `.` 被当成通配符去匹配 `oldXcom`。
 * 3. 匹配到的主机名**整段替换**为固定新域名，不做前缀保留、不做捕获组替换。
 * 4. 协议、userinfo、端口、路径、查询、片段、规则参数块原样保留；不自动升级 HTTP/HTTPS。
 * 5. 已经是新域名的地址返回 null（重复执行不会重复修改）。
 *
 * 数据库侧的批量匹配条件必须与这里保持一致（见 [newAuthorityFor]）。
 */
object UrlDomainReplacement {

    /**
     * 一条生效的替换规则。
     *
     * [newDomain] 永远是**字面量**：不解析 `$1`、`\1` 等正则替换模板。
     */
    data class DomainRule(
        /** 用户原始输入，仅用于展示 */
        val oldInput: String,
        /** 实际参与匹配的正则；裸主机名会被转义成 `\Q...\E` */
        val pattern: String,
        /** 固定目标主机名（小写裸主机名，不含 scheme / 端口 / 路径） */
        val newDomain: String,
        /** 旧域名输入是否被当作字面量主机名处理 */
        val literal: Boolean,
    ) {
        private val regex by lazy(LazyThreadSafetyMode.NONE) {
            // 域名语义本身不区分大小写：主机名已转小写再匹配，模式侧同样忽略大小写，
            // 避免 ^GPT\d+\.EXAMPLE\.COM$ 这类大写正则「永远零匹配」。
            // 需要区分大小写的极端场景可用内联开关 (?-i) 自行关闭。
            Regex(pattern, RegexOption.IGNORE_CASE)
        }

        /** 主机名是否被本条规则命中（整串匹配，天然不会越过主机名边界） */
        fun matchesHost(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            if (h.isEmpty()) return false
            return regex.matches(h)
        }
    }

    enum class InvalidReason {
        EMPTY_OLD,
        EMPTY_NEW,

        /** 旧域名正则语法非法 */
        INVALID_OLD_REGEX,

        /** 正则能匹配空串（如 `.*` / `a*`），几乎必然误写，会命中全部主机 */
        OLD_REGEX_TOO_BROAD,

        /** 新域名非法（含端口 / 路径 / 查询 / 片段 / userinfo / 非法字符） */
        INVALID_NEW,

        /** 字面量旧域名与目标域名完全相同，无需替换 */
        SAME_AS_NEW_DOMAIN,
    }

    sealed class NormalizeResult {
        data class Success(val rule: DomainRule) : NormalizeResult()
        data class Failure(val reason: InvalidReason) : NormalizeResult()
    }

    // 与 AnalyzeUrl.paramPattern 一致：规则参数块形如 ",{...}"
    private val optionPattern = Regex("\\s*,\\s*(?=\\{)")

    /**
     * 裸主机名判定：按 DNS 标签逐段校验。
     *
     * 每个标签只能由字母数字与 `-` 组成，且不能以 `-` 开头或结尾。
     * 因此 `bad-.com` / `-bad.com` / `new..com` / `new_com` 都不算合法主机名
     * （`new_com` 这类会退回「按正则处理」，但正则里它没有元字符，行为不变）。
     */
    private val hostPattern =
        Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

    // ------------------------------------------------------------------ 基础拆解

    /**
     * 把 url 拆成「地址本体」与「规则参数块」。
     * 例如 `https://a.com/b,{"method":"POST"}` -> (`https://a.com/b`, `,{"method":"POST"}`)
     */
    fun splitUrlOption(url: String): Pair<String, String> {
        val match = optionPattern.find(url) ?: return url to ""
        return url.substring(0, match.range.first) to url.substring(match.range.first)
    }

    private class UrlParts(
        /** 形如 "https://" */
        val schemePrefix: String,
        /** 形如 "user:pw@host:8080"，不含 scheme，不含路径 */
        val authority: String,
        /** authority 之后的部分，含路径 / 查询 / 片段 */
        val suffix: String,
    )

    private fun parseHttpUrl(url: String): UrlParts? {
        val lower = url.lowercase()
        val authorityStart = when {
            lower.startsWith("https://") -> 8
            lower.startsWith("http://") -> 7
            else -> return null
        }
        var authorityEnd = url.length
        for (i in authorityStart until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i
                break
            }
        }
        val authority = url.substring(authorityStart, authorityEnd)
        if (authority.isEmpty()) return null
        return UrlParts(
            schemePrefix = url.substring(0, authorityStart),
            authority = authority,
            suffix = url.substring(authorityEnd),
        )
    }

    /** authority 拆成 (userinfo 前缀, host, 端口后缀)；失败返回 null */
    private fun splitAuthority(authority: String): Triple<String, String, String>? {
        if (authority.isEmpty()) return null
        // IPv6 字面量 [::1]:8080 —— 原样保留 host，不参与域名替换
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            return Triple("", authority.substring(0, close + 1), authority.substring(close + 1))
        }
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = authority.substring(at + 1)
        if (hostPort.isEmpty()) return null
        val colon = hostPort.lastIndexOf(':')
        if (colon >= 0 && hostPort.length > colon + 1 &&
            hostPort.substring(colon + 1).all { it.isDigit() }
        ) {
            val host = hostPort.substring(0, colon)
            if (host.isEmpty()) return null
            return Triple(userInfo, host, hostPort.substring(colon))
        }
        return Triple(userInfo, hostPort, "")
    }

    /** 是否绝对的 http(s) 网址（忽略尾部规则参数块） */
    fun isAbsoluteHttpUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val (body, _) = splitUrlOption(url)
        return parseHttpUrl(body) != null
    }

    /** 取主机名（小写），失败返回 null */
    fun hostOf(url: String): String? {
        if (url.isEmpty()) return null
        val (body, _) = splitUrlOption(url)
        val parts = parseHttpUrl(body) ?: return null
        val host = splitAuthority(parts.authority)?.second ?: return null
        return host.lowercase().trimEnd('.')
    }

    /** 从 authority 取主机名（小写），失败返回 null */
    fun hostOfAuthority(authority: String): String? {
        val host = splitAuthority(authority)?.second ?: return null
        return host.lowercase().trimEnd('.')
    }

    /** 取 authority（含 userinfo 与端口），失败返回 null */
    fun authorityOf(url: String): String? {
        if (url.isEmpty()) return null
        val (body, _) = splitUrlOption(url)
        return parseHttpUrl(body)?.authority
    }

    // ------------------------------------------------------------------ 域名替换

    /**
     * 计算替换后的 authority。返回 null 表示「保持原样」：
     * - authority 无法解析；
     * - 主机名没有被规则命中（第三方 CDN、外链、无关域名一律不动）；
     * - 替换前后没有变化（已经就是新域名，重复执行不会重复改）。
     *
     * **端口规则**：端口原样保留。规则只匹配主机名，`host:8080` 会变成 `newhost:8080`；
     * 目标域名输入不接受端口，所以不存在「端口被悄悄改掉」的情况。
     */
    fun newAuthorityFor(authority: String, rule: DomainRule): String? {
        val (userInfo, rawHost, port) = splitAuthority(authority) ?: return null
        val host = rawHost.lowercase().trimEnd('.')
        if (!rule.matchesHost(host)) return null
        val newAuthority = userInfo + rule.newDomain + port
        if (newAuthority.equals(authority, ignoreCase = true)) return null
        return newAuthority
    }

    /**
     * 把 [url] 的主机名按 [rule] 替换。
     *
     * 返回 null 表示保持原样（相对地址、非 http(s)、主机名未命中、或替换后无变化）。
     */
    fun replaceDomain(url: String, rule: DomainRule): String? {
        if (url.isEmpty()) return null
        val (body, option) = splitUrlOption(url)
        val parts = parseHttpUrl(body) ?: return null
        val newAuthority = newAuthorityFor(parts.authority, rule) ?: return null
        return parts.schemePrefix + newAuthority + parts.suffix + option
    }

    /** 书籍两处地址的替换结果，null 表示该字段不需要改 */
    data class BookUrls(val bookUrl: String?, val tocUrl: String?)

    /**
     * 计算一本书 `bookUrl` / `tocUrl` 的替换结果。
     *
     * 返回 null 表示两处地址都没被命中（整本书与本次替换无关）。
     * 只要有一处命中就返回结果，**不会因为另一个字段已经是新域名而跳过**。
     */
    fun replaceBookUrls(
        bookUrl: String,
        tocUrl: String,
        rule: DomainRule,
    ): BookUrls? {
        val newBookUrl = replaceDomain(bookUrl, rule)
        val newTocUrl = replaceDomain(tocUrl, rule)
        if (newBookUrl == null && newTocUrl == null) return null
        return BookUrls(newBookUrl, newTocUrl)
    }

    // ------------------------------------------------------------------ 输入规范化

    /**
     * 解析新域名。
     *
     * 允许：`new.com`、`https://new.com`、`https://new.com/`（仅尾部空路径时容忍）。
     * 不允许：端口、路径、查询、片段、userinfo、空白、非法字符（含 `$` `\` 等正则模板字符）。
     */
    fun parseNewDomain(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (text.any { it.isWhitespace() }) return null
        val schemeIndex = text.indexOf("://")
        if (schemeIndex >= 0) {
            val scheme = text.substring(0, schemeIndex).lowercase()
            if (scheme != "http" && scheme != "https") return null
            text = text.substring(schemeIndex + 3)
        }
        text = text.trimEnd('/')
        if (text.isEmpty()) return null
        // 目标域名必须是裸主机名：端口 / 路径 / 查询 / 片段 / userinfo 一律拒绝，
        // 避免「端口被悄悄丢掉」或「路径被吞进域名」这类不可解释的行为。
        if (text.contains('@') || text.contains('/') || text.contains('?') ||
            text.contains('#') || text.contains(':') || text.contains('[')
        ) {
            return null
        }
        val host = text.lowercase().trimEnd('.')
        if (host.isEmpty()) return null
        if (!hostPattern.matches(host)) return null
        return host
    }

    /**
     * 规范化用户输入的旧域名（正则或裸主机名）与新域名，并把「最终生效的匹配规则」确定下来。
     */
    fun normalize(oldInput: String, newInput: String): NormalizeResult {
        if (oldInput.isBlank()) return NormalizeResult.Failure(InvalidReason.EMPTY_OLD)
        if (newInput.isBlank()) return NormalizeResult.Failure(InvalidReason.EMPTY_NEW)
        val newDomain = parseNewDomain(newInput)
            ?: return NormalizeResult.Failure(InvalidReason.INVALID_NEW)

        val raw = oldInput.trim()
        // 裸主机名 → 字面量转义，避免 "." 被当作通配符
        val literalHost = raw.lowercase().trimEnd('.')
        val literal = hostPattern.matches(literalHost)
        val pattern = if (literal) Regex.escape(literalHost) else raw

        val regex = try {
            Regex(pattern)
        } catch (e: PatternSyntaxException) {
            return NormalizeResult.Failure(InvalidReason.INVALID_OLD_REGEX)
        } catch (e: IllegalArgumentException) {
            return NormalizeResult.Failure(InvalidReason.INVALID_OLD_REGEX)
        }
        // 能匹配空串的正则（.*、a* 等）会命中所有主机名，几乎必然是误写
        if (regex.matches("")) {
            return NormalizeResult.Failure(InvalidReason.OLD_REGEX_TOO_BROAD)
        }
        if (literal && literalHost == newDomain) {
            return NormalizeResult.Failure(InvalidReason.SAME_AS_NEW_DOMAIN)
        }
        return NormalizeResult.Success(
            DomainRule(
                oldInput = raw,
                pattern = pattern,
                newDomain = newDomain,
                literal = literal,
            )
        )
    }
}
