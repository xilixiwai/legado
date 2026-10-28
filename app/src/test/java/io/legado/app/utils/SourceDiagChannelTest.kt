package io.legado.app.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [诊断] 通道行为测试：不依赖「记录日志」开关、事件状态准确、条数受控、不写业务数据。
 *
 * 通过注入临时目录的 [SourceDiagFile] 来验证真实落盘行为，
 * 不触碰 Android 的 externalCacheDir，也不改动任何业务设置。
 */
class SourceDiagChannelTest {

    private lateinit var dir: File
    private lateinit var sink: SourceDiagFile

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("source-diag-channel").toFile()
        sink = SourceDiagFile(dir, maxBytes = 1L shl 20, retentionMs = 60_000)
        SourceDiag.sinkOverride = sink
    }

    @After
    fun tearDown() {
        SourceDiag.sinkOverride = null
        sink.close()
        dir.deleteRecursively()
    }

    private fun readLog(): String = File(dir, SourceDiag.FILE_NAME).readText()

    @Test
    fun `未开启记录日志时关键事件仍写入独立诊断文件`() {
        SourceDiag.sourceSave("https://old.example.com", "https://new.example.com", true)
        SourceDiag.migrateBegin(3)
        val text = readLog()
        assertTrue(text.contains("SOURCE_DIAG"))
        assertTrue(text.contains("SOURCE_SAVE"))
        assertTrue(text.contains("urlChanged=true"))
        assertTrue(text.contains("https://old.example.com"))
        assertTrue(text.contains("https://new.example.com"))
        assertTrue(text.contains("SOURCE_MIGRATE_BEGIN"))
        assertTrue(text.contains("affectedBooks=3"))
    }

    @Test
    fun `书源查找成功与失败都输出准确状态`() {
        SourceDiag.lookup("phaseA", "https://a.com/b/1", "https://a.com", true)
        SourceDiag.lookup("phaseA", "https://a.com/b/2", "https://gone.com", false)
        val text = readLog()
        assertTrue(text.contains("phase=phaseA found=true"))
        assertTrue(text.contains("phase=phaseA found=false"))
    }

    @Test
    fun `点击被拦下时记录具体原因`() {
        SourceDiag.click(
            bookUrl = "https://a.com/book/1",
            oldBookUrl = "https://a.com/book/1",
            origin = "https://new.com",
            oldOrigin = "https://old.com"
        )
        val text = readLog()
        assertTrue(text.contains("gate=skip"))
        assertTrue(text.contains("sameUrl=true"))
        assertTrue(text.contains("sameOrigin=false"))
    }

    @Test
    fun `点击非当前地址时记录为放行`() {
        SourceDiag.click("https://a.com/book/2", "https://a.com/book/1", null, null)
        val text = readLog()
        assertTrue(text.contains("gate=pass"))
        assertTrue(text.contains("sameUrl=false"))
    }

    @Test
    fun `迁移失败记录到具体步骤与异常类型`() {
        SourceDiag.migrateFail(3, "https://old.com", "https://new.com", IllegalStateException("boom"))
        val text = readLog()
        assertTrue(text.contains("SOURCE_MIGRATE_FAIL"))
        assertTrue(text.contains("step=3"))
        assertTrue(text.contains("type=IllegalStateException"))
        assertFalse(text, text.contains("boom"))
    }

    @Test
    fun `迁移开始与提交成功是两个可区分的事件`() {
        SourceDiag.migrateBegin(2)
        SourceDiag.migrateCommit("https://old.com", "https://new.com")
        val text = readLog()
        assertTrue(text.contains("SOURCE_MIGRATE_BEGIN"))
        assertTrue(text.contains("SOURCE_MIGRATE_COMMIT"))
        assertTrue(text.contains("committed=true"))
        // 只有 COMMIT 事件带 committed 语义，BEGIN 不能携带成功标记
        val begin = text.substringAfter("SOURCE_MIGRATE_BEGIN").substringBefore("SOURCE_MIGRATE_COMMIT")
        assertFalse(begin, begin.contains("committed"))
        assertFalse(begin, begin.contains("success"))
    }

    @Test
    fun `导入汇总区分新增更新跳过并把失败数标记为不可用`() {
        SourceDiag.importSummary(total = 10, applied = 4, newCount = 2, updateCount = 1, skipCount = 1)
        val text = readLog()
        assertTrue(text.contains("IMPORT_SUMMARY"))
        assertTrue(text.contains("total=10 applied=4 new=2 update=1 skip=1 failed=na"))
        // 不再输出逐条明细
        assertFalse(text, text.contains("IMPORT_ITEM"))
    }

    @Test
    fun `导入失败事件带脱敏来源且不泄漏规则参数`() {
        SourceDiag.importFail(
            IllegalStateException("boom"),
            "https://a.com/x,{\"method\":\"POST\",\"body\":\"SECRET\"}"
        )
        val text = readLog()
        assertTrue(text.contains("IMPORT_FAIL"))
        assertTrue(text.contains("src=https://a.com#fp="))
        assertTrue(text.contains("type=IllegalStateException"))
        assertFalse(text, text.contains("SECRET"))
        assertFalse(text, text.contains("method"))
        assertFalse(text, text.contains("boom"))
    }

    @Test
    fun `同一状态重复触发时不重复刷屏`() {
        repeat(5) {
            SourceDiag.logOnChange("KEY-1", "LOOKUP", "phase=dedupe found=false")
        }
        SourceDiag.logOnChange("KEY-1", "LOOKUP", "phase=dedupe found=true")
        val text = readLog()
        assertEquals(1, DiagSourceText.countOf(text, "phase=dedupe found=false"))
        assertEquals(1, DiagSourceText.countOf(text, "phase=dedupe found=true"))
    }

    @Test
    fun `诊断通道不依赖记录日志开关`() {
        val src = DiagSourceText.source("app/src/main/java/io/legado/app/utils/SourceDiag.kt")
        assertFalse(src, src.contains("AppConfig.recordLog"))
        assertFalse(src, src.contains("LogUtils.d"))
        assertTrue(src, src.contains("externalCacheDir"))
        assertTrue(src, src.contains("MAX_BYTES"))
        assertTrue(src, src.contains("RETENTION_MS"))
    }

    @Test
    fun `诊断通道不触发任何数据库或书籍写入`() {
        val src = DiagSourceText.source("app/src/main/java/io/legado/app/utils/SourceDiag.kt")
        listOf("appDb", "Dao", "execSQL", "BookHelp", "updateCacheFolder", "SourceConfig")
            .forEach { forbidden ->
                assertFalse("诊断通道不应出现 $forbidden", src.contains(forbidden))
            }
    }

    @Test
    fun `诊断通道不再包含会话与逐条导入机制`() {
        val src = DiagSourceText.source("app/src/main/java/io/legado/app/utils/SourceDiag.kt")
        listOf(
            "ensureSession", "bootId", "logOnce", "importItem", "importHostOverlap",
            "IMPORT_ITEM_LIMIT", "beginImportSession", "changeSourceList", "migrateCache"
        ).forEach { removed ->
            assertFalse("已移除的机制不应残留: $removed", src.contains(removed))
        }
    }
}
