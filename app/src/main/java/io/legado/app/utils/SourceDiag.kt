package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.help.book.UrlDomainReplacement
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * [诊断] 书源身份 / 域名变更链路的独立诊断通道。
 *
 * 为什么不直接用现有两套日志：
 * - [AppLog] 只是内存环形缓冲（上限 100 条），进程结束即丢；
 * - [LogUtils] 的文件日志受「记录日志」开关控制，开关默认关闭时 FileHandler 的
 *   level 被置为 `Level.OFF`，一个字都不会落盘。
 *
 * 所以这里单独开一个**受控**的诊断文件，只做三件事：落盘、脱敏、限量。
 * - 与常规日志同目录（`externalCacheDir/logs`），复用 [FileUtils] 建目录；
 *   不改「记录日志」开关，也不接管任何其它应用日志；
 * - 单文件上限 [MAX_BYTES]，超限滚动为 [BACKUP_FILE_NAME]，总量上限 `2 × MAX_BYTES`；
 * - 保留期 [RETENTION_MS]，进程内首次写入时清掉超期文件；
 * - 通道内部吞掉所有异常，绝不影响业务，也不触发任何写库 / 修复动作。
 *
 * 覆盖范围只针对两个目标现象：
 * 1. 改书源域名后旧书显示「无书源」——`SOURCE_SAVE*` / `SOURCE_MIGRATE_*` / `IMPORT_*` / `LOOKUP`；
 * 2. 换源候选条目点不动——`CLICK` / `CHANGE_SOURCE`。
 * 阅读器缓存、书架批量更新等其它同样会读 `books.origin` 的入口**不在**本通道范围内。
 *
 * 脱敏约定：URL 只输出 `scheme://host[:port]` 加不可逆指纹；
 * 解析失败时输出固定标记 [REDACTED]，**绝不回退成原始 URL**。
 * 不输出书名、完整书籍 URL、完整书源规则、Cookie / Token / 任何凭据。
 */
object SourceDiag {

    /** 统一前缀，便于在文件里 grep 出全部诊断行 */
    const val PREFIX = "SOURCE_DIAG"

    /** 无法可靠脱敏时的固定隐藏标记 */
    const val REDACTED = "<redacted>"

    /** 字段缺失时的占位 */
    const val EMPTY = "-"

    /** 现有调用链无法可靠获取的计数，明确标记为不可用，不做推断 */
    const val NA = "na"

    const val DIR_NAME = "logs"
    const val FILE_NAME = "source-diag.txt"
    const val BACKUP_FILE_NAME = "source-diag.1.txt"

    /** 单文件字节上限 */
    const val MAX_BYTES = 256L * 1024L

    /** 文件保留期：3 天 */
    const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000

    /** 单行字符上限，防止异常消息把文件撑爆 */
    private const val MAX_LINE_CHARS = 1200

    /** 去重状态表的 key 上限，超过直接清空，避免无限增长 */
    private const val MAX_STATE_KEYS = 200

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private val lastState = Collections.synchronizedMap(HashMap<String, String>())

    /** 单元测试注入用；生产环境为 null，走 [createDefaultSink] */
    @Volatile
    internal var sinkOverride: SourceDiagFile? = null

    @Volatile
    private var defaultSink: SourceDiagFile? = null

    @Volatile
    private var defaultResolved = false

    // ------------------------------------------------------------------ 通道本体

    internal fun sink(): SourceDiagFile? {
        sinkOverride?.let { return it }
        if (!defaultResolved) {
            synchronized(this) {
                if (!defaultResolved) {
                    defaultSink = createDefaultSink()
                    defaultResolved = true
                }
            }
        }
        return defaultSink
    }

    private fun createDefaultSink(): SourceDiagFile? {
        return try {
            val root = appCtx.externalCacheDir ?: return null
            val dir = FileUtils.createFolderIfNotExist(root, DIR_NAME)
            SourceDiagFile(dir)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 写一条诊断记录。
     *
     * 注意：**不看**「记录日志」这个偏好开关，因此该开关关闭时同样落盘；
     * 也不调用 [LogUtils]，不会污染常规应用日志文件。
     *
     * @param intoAppLog 是否同时放进应用内日志弹窗（上限 100 条的缓冲），
     *   只给异常类事件用 true，正常事件保持 false，避免把真正的错误挤掉。
     */
    fun log(event: String, detail: String = "", intoAppLog: Boolean = false) {
        val line = buildLine(event, detail)
        runCatching { sink()?.append(line) }
        if (intoAppLog) {
            runCatching { AppLog.putNotSave(line) }
        }
    }

    /** 同一 key 只在「状态串发生变化」时输出，避免会被反复触达的读取路径重复刷屏 */
    fun logOnChange(key: String, event: String, detail: String, intoAppLog: Boolean = false) {
        if (lastState.size > MAX_STATE_KEYS) lastState.clear()
        val previous = lastState.put(key, detail)
        if (previous == detail) return
        log(event, detail, intoAppLog)
    }

    private fun buildLine(event: String, detail: String): String {
        val body = buildString {
            append(PREFIX).append(' ')
            append(timeFormat.get()?.format(Date()) ?: EMPTY)
            append(' ').append(event)
            if (detail.isNotEmpty()) append(' ').append(detail)
        }
        if (body.length <= MAX_LINE_CHARS) return body
        return body.substring(0, MAX_LINE_CHARS) + "…<truncated>"
    }

    // ------------------------------------------------------------------ 脱敏

    /**
     * 脱敏 URL：只保留 `scheme://host[:port]` 与整串的不可逆指纹。
     *
     * 顺序很重要：**先**用 [UrlDomainReplacement.splitUrlOption] 切掉 `,{...}` 规则参数块，
     * **再**解析基础地址。只依赖 [DiagnosticUtils.sanitizeUrl] 是不够的，
     * 它不会去掉规则参数块，而参数块里常常带 method / body / header 等敏感内容。
     *
     * 任何一步解析失败都返回 [REDACTED]，不会退化成原始字符串。
     */
    fun maskUrl(url: String?): String {
        if (url.isNullOrBlank()) return EMPTY
        val (body, _) = UrlDomainReplacement.splitUrlOption(url)
        val base = schemeAndHost(body) ?: return REDACTED
        return "$base#fp=${fingerprint(url)}"
    }

    private fun schemeAndHost(body: String): String? {
        val scheme = when {
            body.startsWith("https://", ignoreCase = true) -> "https"
            body.startsWith("http://", ignoreCase = true) -> "http"
            else -> return null
        }
        val host = UrlDomainReplacement.hostOf(body) ?: return null
        val port = portOf(body)
        return if (port != null) "$scheme://$host:$port" else "$scheme://$host"
    }

    /** 端口单独取：authority 里可能含 userinfo，不能整段输出 */
    private fun portOf(body: String): String? {
        val authority = UrlDomainReplacement.authorityOf(body) ?: return null
        val hostPort = authority.substringAfterLast('@')
        val idx = hostPort.lastIndexOf(':')
        if (idx < 0) return null
        val port = hostPort.substring(idx + 1)
        return if (port.isNotEmpty() && port.all { it.isDigit() }) port else null
    }

    /** 不可逆指纹：只用于判断「两条地址是否同一串」，无法反推出原文 */
    fun fingerprint(value: String?): String {
        if (value.isNullOrBlank()) return EMPTY
        return runCatching { MD5Utils.md5Encode16(value) }.getOrDefault(REDACTED)
    }

    // ------------------------------------------------------------------ 事件：书源保存 / 迁移

    /** 保存被受理（无论是否换域名），事务开始前调用 */
    fun sourceSave(oldUrl: String?, newUrl: String?, urlChanged: Boolean) {
        log("SOURCE_SAVE", "old=${maskUrl(oldUrl)} new=${maskUrl(newUrl)} urlChanged=$urlChanged")
    }

    /** 新地址已被其它书源占用，本次保存被拦下 */
    fun sourceSaveBlocked(newUrl: String?, reason: String) {
        log(
            "SOURCE_SAVE_BLOCKED",
            "new=${maskUrl(newUrl)} reason=$reason",
            intoAppLog = true
        )
    }

    /** 七步迁移开始（在 `runInTransaction` 之前），与 [migrateCommit] 成对判读 */
    fun migrateBegin(affectedBooks: Int) {
        log("SOURCE_MIGRATE_BEGIN", "steps=7 affectedBooks=$affectedBooks")
    }

    /** 单步完成。**只记录已执行完的步骤**，不能当作迁移成功 */
    fun migrateStep(step: Int, detail: String = "") {
        log(
            "SOURCE_MIGRATE_STEP",
            "step=$step" + (if (detail.isEmpty()) "" else " $detail")
        )
    }

    /** 迁移失败：`step` 为**失败的那一步**（1..7），事务已回滚 */
    fun migrateFail(step: Int, oldUrl: String?, newUrl: String?, error: Throwable?) {
        log(
            "SOURCE_MIGRATE_FAIL",
            "step=$step old=${maskUrl(oldUrl)} new=${maskUrl(newUrl)} " +
                    "type=${DiagnosticUtils.exceptionType(error)}",
            intoAppLog = true
        )
    }

    /**
     * 事务已提交。**必须在 `runInTransaction` 返回之后**调用，
     * 提交之前不允许记录任何「成功」语义的事件。
     */
    fun migrateCommit(oldUrl: String?, newUrl: String?) {
        log("SOURCE_MIGRATE_COMMIT", "old=${maskUrl(oldUrl)} new=${maskUrl(newUrl)} committed=true")
    }

    // ------------------------------------------------------------------ 事件：书源导入

    /**
     * 导入汇总，**只在 `insertBookSource` 已返回之后**调用，一条事件代替逐条明细。
     *
     * 各计数的来源与可靠性：
     * - `total`：本次对比的书源总数；
     * - `applied`：用户实际勾选并提交的条数；
     * - `new` / `update` / `skip`：取自对比阶段写入的状态数组，即**实际提交集合**内的
     *   新增 / 更新 / 无变化条数；
     * - `failed`：`SourceHelp.insertBookSource` 不返回逐条失败信息，18+ 站点还会被
     *   静默拦下，**无法可靠统计**，因此固定输出 [NA]，不推断、不伪造。
     */
    fun importSummary(total: Int, applied: Int, newCount: Int, updateCount: Int, skipCount: Int) {
        log(
            "IMPORT_SUMMARY",
            "total=$total applied=$applied new=$newCount update=$updateCount " +
                    "skip=$skipCount failed=$NA"
        )
    }

    /**
     * 导入失败（下载 / 解析 / 落库任一段抛错）。
     *
     * @param source 出错时的导入来源，可能是 URL、content:// 或整段 JSON；
     *   只输出脱敏结果，非 http(s) 一律 [REDACTED]，不会把原文写进文件。
     */
    fun importFail(error: Throwable?, source: String? = null) {
        log(
            "IMPORT_FAIL",
            "src=${maskUrl(source)} type=${DiagnosticUtils.exceptionType(error)}",
            intoAppLog = true
        )
    }

    // ------------------------------------------------------------------ 事件：书源查找

    /**
     * 书籍 → 书源查找结果。
     *
     * @param found `getBookSource(book.origin)` 是否取到书源
     * @param intoAppLog 默认只在「没找到」时进应用内日志
     * @param dedupe 为 true 时只在结果状态变化时输出，用于会被反复触达的读取路径
     */
    fun lookup(
        phase: String,
        bookUrl: String?,
        origin: String?,
        found: Boolean,
        extra: String = "",
        intoAppLog: Boolean = !found,
        dedupe: Boolean = false
    ) {
        val detail = "phase=$phase found=$found origin=${maskUrl(origin)} " +
                "book=${fingerprint(bookUrl)}" +
                (if (extra.isEmpty()) "" else " $extra")
        if (dedupe) {
            logOnChange("LOOKUP#$phase#$bookUrl", "LOOKUP", detail, intoAppLog)
        } else {
            log("LOOKUP", detail, intoAppLog)
        }
    }

    // ------------------------------------------------------------------ 事件：换源

    /**
     * 换源列表点击。
     *
     * `gate=skip` 表示点击被 `if (it.bookUrl != oldBookUrl)` 拦下。
     * 关键区分：
     * - `sameUrl=true  sameOrigin=true`  → 正常地又点了当前源；
     * - `sameUrl=true  sameOrigin=false` → 地址相同但书源不同，属于异常形态。
     */
    fun click(bookUrl: String?, oldBookUrl: String?, origin: String?, oldOrigin: String?) {
        val sameUrl = bookUrl == oldBookUrl
        val sameOrigin = origin == oldOrigin
        val anomaly = sameUrl && !sameOrigin
        log(
            "CLICK",
            "gate=${if (sameUrl) "skip" else "pass"} sameUrl=$sameUrl " +
                    "sameOrigin=$sameOrigin origin=${maskUrl(origin)} " +
                    "oldOrigin=${maskUrl(oldOrigin)} book=${fingerprint(bookUrl)}",
            intoAppLog = anomaly
        )
    }

    fun changeSource(phase: String, detail: String = "", intoAppLog: Boolean = false) {
        log(
            "CHANGE_SOURCE",
            "phase=$phase" + (if (detail.isEmpty()) "" else " $detail"),
            intoAppLog = intoAppLog
        )
    }
}

/**
 * 受控诊断文件：单文件滚动 + 保留期，任何异常都不外抛。
 *
 * 独立成类是为了能在 JVM 单元测试里用临时目录直接验证
 * 大小上限、滚动与保留期，而不依赖 Android 的 `externalCacheDir`。
 */
class SourceDiagFile(
    private val dir: File,
    private val maxBytes: Long = SourceDiag.MAX_BYTES,
    private val retentionMs: Long = SourceDiag.RETENTION_MS,
    private val fileName: String = SourceDiag.FILE_NAME,
    private val backupFileName: String = SourceDiag.BACKUP_FILE_NAME,
) {

    private val lock = Any()
    private var out: FileOutputStream? = null
    private var size = 0L

    fun append(line: String) {
        synchronized(lock) {
            try {
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                prepare(bytes.size)
                val stream = out ?: return
                stream.write(bytes)
                stream.flush()
                size += bytes.size
            } catch (e: Throwable) {
                // 诊断通道自身失败时静默降级：绝不向业务抛异常
                closeQuietly()
            }
        }
    }

    fun close() {
        synchronized(lock) { closeQuietly() }
    }

    private fun prepare(incoming: Int) {
        val current = File(dir, fileName)
        if (out == null) {
            if (!dir.isDirectory) dir.mkdirs()
            expireStale(current, File(dir, backupFileName))
            out = FileOutputStream(current, true)
            size = current.length()
        }
        if (size > 0 && size + incoming > maxBytes) {
            rotate(current)
        }
    }

    /** 只在进程内首次打开时检查一次：超期文件属于「上一次运行留下的」 */
    private fun expireStale(vararg files: File) {
        val now = System.currentTimeMillis()
        files.forEach { file ->
            if (file.isFile && now - file.lastModified() > retentionMs) {
                file.delete()
            }
        }
    }

    private fun rotate(current: File) {
        closeQuietly()
        val backup = File(dir, backupFileName)
        if (backup.isFile) backup.delete()
        current.renameTo(backup)
        // 即使 rename 失败，也会以截断方式重开，总量仍然有界
        out = FileOutputStream(current, false)
        size = 0L
    }

    private fun closeQuietly() {
        try {
            out?.flush()
        } catch (_: Throwable) {
        }
        try {
            out?.close()
        } catch (_: Throwable) {
        }
        out = null
    }
}
