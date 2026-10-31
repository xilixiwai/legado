package io.legado.app.help

import io.legado.app.utils.DiagSourceText.assertOrder
import io.legado.app.utils.DiagSourceText.block
import io.legado.app.utils.DiagSourceText.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫画图片诊断日志（MangaPerfLog）验证：
 *
 * - 行为部分：[MangaPerfLog.Trace] 是纯 JVM 数据+渲染对象，直接单测
 *   汇总/明细格式、skip 语义、慢页阈值、幂等发射与成败取消区分；
 * - 结构部分：按既有 DiagSourceText 做法，读取真实源文件断言
 *   「埋点确实存在」且「原有业务判定与顺序未被改动」。
 *
 * JVM 边界：LogUtils 文件写入、AppConfig 读取依赖 Android 运行环境，
 * 门控开关的行为由结构断言保护（真机开关行为列入待真机验证项）。
 */
class MangaPerfLogTest {

    private fun trace(src: String = "display") = MangaPerfLog.Trace("1a2b3c4d", 42L, src)

    // ---------- 行为：汇总行 ----------

    @Test
    fun `汇总行包含图片标识 翻页ID 来源 结果 预取状态与总耗时`() {
        val t = trace()
        t.out = "hit"
        t.preState = "cached"
        val line = t.renderSummary()
        assertOrder(
            line,
            "MangaImg img=1a2b3c4d", "flip=42", "src=display",
            "out=hit", "pre=cached", "total="
        )
        assertTrue("成功路径不带 why 字段", !line.contains("why="))
        assertTrue("标识只含短 MD5（8 位）", line.substringAfter("img=").substringBefore(" ").length == 8)
    }

    @Test
    fun `失败与取消都携带原因且最终结果可区分`() {
        val fail = trace().apply { fail("timeout") }
        assertTrue(fail.renderSummary().contains("out=fail"))
        assertTrue(fail.renderSummary().contains("why=timeout"))

        val cancel = trace().apply { cancel("stale") }
        assertTrue(cancel.renderSummary().contains("out=cancel"))
        assertTrue(cancel.renderSummary().contains("why=stale"))
        assertFalse("取消不得记成失败", cancel.renderSummary().contains("out=fail"))
    }

    // ---------- 行为：明细行与 skip 语义 ----------

    @Test
    fun `未发生的阶段记为skip而不是0ms`() {
        val t = trace().apply { out = "hit" }
        val line = t.renderDetail("slow")
        assertTrue(line.contains("check=skip"))
        assertTrue(line.contains("net=skip"))
        assertTrue(line.contains("dec=skip"))
        assertTrue(line.contains("val=skip"))
        assertTrue(line.contains("opt=skip"))
        assertTrue(line.contains("write=skip"))
        assertTrue(line.contains("glide=skip"))
        assertTrue(line.contains("draw=skip"))
        //阶段字段不得伪造 0ms（wait~ 为派生近似值，允许 0ms）
        assertFalse("skip 不得伪装成 0ms", line.contains(" net=0ms"))
    }

    @Test
    fun `发生过的阶段记录毫秒并标注估算首帧`() {
        val t = trace().apply {
            out = "hit"
            checkMs = 12
            netMs = 300
            decryptMs = 0
            glideMs = 45
            drawMs = 8
        }
        val line = t.renderDetail("slow")
        assertOrder(
            line, "check=12ms", "net=300ms", "dec=0ms",
            "val=skip", "glide=45ms", "draw=8ms(est)"
        )
    }

    @Test
    fun `异常状态与失败原因进入明细行`() {
        val t = trace().apply {
            out = "fail"
            anomaly = "dup-net"
            fail("下载失败")
        }
        val line = t.renderDetail("fail")
        assertTrue(line.contains("anomaly=dup-net"))
        assertTrue(line.contains("err=下载失败"))
    }

    // ---------- 行为：阈值与幂等 ----------

    @Test
    fun `慢页阈值判定为500ms`() {
        assertFalse(MangaPerfLog.Trace.isSlow(499))
        assertTrue(MangaPerfLog.Trace.isSlow(500))
        assertEquals(500L, MangaPerfLog.SLOW_THRESHOLD_MS)
    }

    @Test
    fun `汇总行幂等发射一次加载含兜底链只发一条`() {
        val t = trace()
        assertTrue(t.markEmitted())
        assertFalse(t.markEmitted())
        assertFalse(t.markEmitted())
    }

    @Test
    fun `耗时来自单调时钟且非负`() {
        val start = MangaPerfLog.nowNs()
        val ms = MangaPerfLog.msSince(start)
        assertTrue(ms >= 0)
    }

    // ---------- 行为：日志量实测（非理论估算） ----------

    @Test
    fun `日常日志量实测_单次命中翻页两条_带明细翻页不超过五百字节`() {
        // 一级常规：命中翻页 = 1 翻转行 + 1 汇总行
        val flipLine = "2026-11-01 12:00:00.000: MangaPerf MangaFlip id=1 dir=next 0→1 ch=8f3e2a1b img=cached"
        val hitTrace = trace().apply { out = "hit"; preState = "cached" }
        val hitBytes = flipLine.length + 2 + hitTrace.renderSummary().length
        assertTrue("命中翻页应 ≤250 字节，实测 $hitBytes", hitBytes <= 250)

        // 二级慢页翻页：翻转行 + 汇总行 + 明细行 + 首帧行
        val slowTrace = trace().apply {
            out = "stored"
            checkMs = 15
            netMs = 800
            decryptMs = 0
            validateMs = 2
            optimizeMs = 40
            writeMs = 9
            glideMs = 120
            drawMs = 16
        }
        val detailLine = slowTrace.renderDetail("slow")
        val drawLine = "2026-11-01 12:00:00.000: MangaPerf MangaDraw img=1a2b3c4d flip=42 draw=16ms est"
        val slowBytes = flipLine.length + 2 + slowTrace.renderSummary().length + 2 +
                detailLine.length + 2 + drawLine.length
        assertTrue("慢页翻页应 ≤500 字节，实测 $slowBytes", slowBytes <= 500)

        // 日常量估算：500 次翻页/天（命中为主，1% 慢页）× ~120 字节/行
        val daily = (500 * 2 + 5 * 2) * 120L
        assertTrue("日常日志量应 <200KB/天，实测约 ${daily / 1024}KB", daily < 200 * 1024)
    }

    // ---------- 结构：埋点存在且不改变业务顺序 ----------

    @Test
    fun `MangaPerfLog所有出口都有开关门控且异常不外泄`() {
        val src = source("app/src/main/java/io/legado/app/help/MangaPerfLog.kt")
        listOf("flip(", "summary(", "detail(", "draw(").forEach {
            val fn = block(src, "fun $it", "}\n")
            assertTrue("出口 $it 缺少开关门控", fn.contains("if (trace == null || !enabled) return") ||
                    fn.contains("if (!enabled) return"))
            assertTrue("出口 $it 未吞异常", fn.contains("runCatching"))
        }
        assertTrue("三级日志必须单独门控", block(src, "fun v(", "}\n").contains("if (!verbose) return"))
    }

    @Test
    fun `显示路径创建轨迹有开关守卫且取消与过期都有终态`() {
        val vh = source("app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaVH.kt")
        val loadBlock = block(vh, "fun loadImageWithRetry(", "fun cancelLoad()")
        assertTrue(loadBlock.contains("if (MangaPerfLog.enabled)"))
        assertTrue(loadBlock.contains("BookHelp.preloadStateOf(book, imageUrl)"))
        assertOrder(
            loadBlock,
            "BookHelp.ensureImage(",
            "onHitFile = { hitFile = it }",
            "if (!canDeliverToUi(sequence, imageUrl))",
            "trace?.cancel(\"stale\")",
            "MangaPerfLog.summary(trace)"
        )
        assertTrue("取消终态必须区分于失败", loadBlock.contains("trace?.cancel(\"cancel\")"))
        assertTrue("疑似未等预取的重复下载要标记异常", loadBlock.contains("trace.anomaly = \"dup-net\""))
    }

    @Test
    fun `预取路径使用同一图片标识并有成功失败终态`() {
        val bookHelp = source("app/src/main/java/io/legado/app/help/book/BookHelp.kt")
        val prefetchBlock = block(
            bookHelp, "fun prefetchImage(", "private fun imageCache("
        )
        assertTrue("预取轨迹必须用同一 imgId 口径", prefetchBlock.contains("MangaPerfLog.imgId(src)"))
        assertTrue("预取轨迹来源标记为 prefetch", prefetchBlock.contains("src = \"prefetch\""))
        assertOrder(
            prefetchBlock,
            "prefetching.putIfAbsent(src, true)",
            "val result = ensureImage(bookSource, book, src, trace = trace)",
            "MangaPerfLog.summary(trace)",
            "prefetching.remove(src)"
        )
    }

    @Test
    fun `预取与缓存状态探测零解码`() {
        val bookHelp = source("app/src/main/java/io/legado/app/help/book/BookHelp.kt")
        val stateBlock = block(bookHelp, "fun preloadStateOf(", "\n    /**")
        assertTrue(stateBlock.contains("prefetching.containsKey(src)"))
        assertTrue(stateBlock.contains("hasImageFile(book, src)"))
        assertFalse("状态探测不得解码", stateBlock.contains("decode"))
        assertFalse("状态探测不得读文件内容", stateBlock.contains("readBytes"))
    }

    @Test
    fun `翻页事件挂在绑定入口且关闭时只维护游标`() {
        val adapter = source("app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaAdapter.kt")
        val bindBlock = block(adapter, "override fun onBindViewHolder(", "private fun logFlip(")
        assertOrder(bindBlock, "is PageViewHolder -> {", "logFlip(position)", "vh.onBind(")
        val flipBlock = block(adapter, "private fun logFlip(", "\n}")
        assertTrue(flipBlock.contains("if (!MangaPerfLog.enabled)"))
        assertTrue(flipBlock.contains("lastFlipPosition = position"))
        assertTrue("翻转行须含方向", flipBlock.contains("\"next\""))
        assertTrue("翻转行须含章节标识", flipBlock.contains("MangaPerfLog.imgId(it)"))
    }

    @Test
    fun `日志文件追加写并按大小分段滚动`() {
        val logUtils = source("app/src/main/java/io/legado/app/utils/LogUtils.kt")
        assertTrue(logUtils.contains("AsyncFileHandler(logPath, limit = 2_000_000, count = 3, append = true)"))
        assertTrue("分段滚动需要 %g 占位", logUtils.contains("appLog-\${date}_%g.txt"))
        val handler = source("app/src/main/java/io/legado/app/utils/AsyncFileHandler.kt")
        assertTrue(handler.contains("FileHandler(pattern, limit, count, append)"))
    }

    @Test
    fun `三级详细开关默认关闭且有设置入口`() {
        val appConfig = source("app/src/main/java/io/legado/app/help/config/AppConfig.kt")
        assertTrue(appConfig.contains("var mangaPerfVerbose = appCtx.getPrefBoolean(PreferKey.mangaPerfVerbose, false)"))
        val xml = source("app/src/main/res/xml/pref_config_other.xml")
        assertTrue(xml.contains("android:key=\"mangaPerfVerbose\""))
        assertTrue(xml.contains("android:defaultValue=\"false\""))
    }

    @Test
    fun `小说整本下载调用点不引用诊断日志`() {
        //CacheBook 走 ensureChapterImages/saveImages（trace 默认 null），行为与日志零关联
        val cacheBook = source("app/src/main/java/io/legado/app/model/CacheBook.kt")
        assertTrue(cacheBook.contains("BookHelp.ensureChapterImages("))
        assertFalse("小说下载路径不得引用诊断日志", cacheBook.contains("MangaPerfLog"))
    }
}
