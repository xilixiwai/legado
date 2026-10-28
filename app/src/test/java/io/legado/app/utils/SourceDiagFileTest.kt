package io.legado.app.utils

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [诊断] 受控诊断文件的大小上限 / 滚动 / 保留期 / 容错单元测试。
 *
 * 目的：证明「持续诊断不会导致日志无限增长」，并且通道自身故障时不影响业务。
 */
class SourceDiagFileTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("source-diag-file").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `写入内容可读回且每行一条`() {
        val file = SourceDiagFile(dir, maxBytes = 4096, retentionMs = 60_000)
        file.append("SOURCE_DIAG A x=1")
        file.append("SOURCE_DIAG B y=2")
        file.close()

        val text = File(dir, SourceDiag.FILE_NAME).readText()
        assertTrue(text.contains("SOURCE_DIAG A x=1"))
        assertTrue(text.contains("SOURCE_DIAG B y=2"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `续写不覆盖已有内容`() {
        File(dir, SourceDiag.FILE_NAME).writeText("PRE\n")
        val file = SourceDiagFile(dir, maxBytes = 4096, retentionMs = 60_000)
        file.append("POST")
        file.close()

        val text = File(dir, SourceDiag.FILE_NAME).readText()
        assertTrue(text.contains("PRE"))
        assertTrue(text.contains("POST"))
    }

    @Test
    fun `超过单文件上限时滚动且总量有界`() {
        val maxBytes = 200L
        val file = SourceDiagFile(dir, maxBytes = maxBytes, retentionMs = 60_000)
        repeat(60) { index ->
            file.append("SOURCE_DIAG LINE ${"x".repeat(40)} i=$index")
        }
        file.close()

        val current = File(dir, SourceDiag.FILE_NAME)
        val backup = File(dir, SourceDiag.BACKUP_FILE_NAME)
        assertTrue("当前文件超限: ${current.length()}", current.length() <= maxBytes)
        assertTrue("未发生滚动", backup.isFile)
        assertTrue("备份文件超限: ${backup.length()}", backup.length() <= maxBytes)
        assertTrue(
            "总量超过 2 倍上限: ${current.length() + backup.length()}",
            current.length() + backup.length() <= maxBytes * 2
        )
    }

    @Test
    fun `超期文件在打开时被清理`() {
        val current = File(dir, SourceDiag.FILE_NAME)
        current.writeText("OLD-CONTENT\n")
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        assertTrue(current.setLastModified(eightDaysAgo))

        val file = SourceDiagFile(
            dir,
            maxBytes = 4096,
            retentionMs = 3L * 24 * 60 * 60 * 1000
        )
        file.append("NEW-CONTENT")
        file.close()

        val text = current.readText()
        assertFalse("超期内容未被清理", text.contains("OLD-CONTENT"))
        assertTrue(text.contains("NEW-CONTENT"))
    }

    @Test
    fun `未超期文件不会被清理`() {
        val current = File(dir, SourceDiag.FILE_NAME)
        current.writeText("RECENT\n")

        val file = SourceDiagFile(dir, maxBytes = 4096, retentionMs = 3L * 24 * 60 * 60 * 1000)
        file.append("APPEND")
        file.close()

        val text = current.readText()
        assertTrue(text.contains("RECENT"))
        assertTrue(text.contains("APPEND"))
    }

    @Test
    fun `目录不可用时静默降级不抛异常`() {
        val blocker = File(dir, "blocker")
        blocker.writeText("i am a file, not a directory")

        val file = SourceDiagFile(blocker, maxBytes = 1024, retentionMs = 60_000)
        // 不抛异常即为通过
        file.append("SOURCE_DIAG X")
        file.append("SOURCE_DIAG Y")
        file.close()
    }

    @Test
    fun `重复 close 不抛异常`() {
        val file = SourceDiagFile(dir, maxBytes = 4096, retentionMs = 60_000)
        file.append("SOURCE_DIAG Z")
        file.close()
        file.close()
        assertTrue(File(dir, SourceDiag.FILE_NAME).readText().contains("SOURCE_DIAG Z"))
    }
}
