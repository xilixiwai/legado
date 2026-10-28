package io.legado.app.utils

import org.junit.Assert.assertTrue
import java.io.File

/**
 * 诊断相关测试共用的「源码断言」工具。
 *
 * 与既有 [io.legado.app.model.DiagnosticLogInsertionTest] 保持同一套做法：
 * 读取真实源文件做文本断言，用来证明「日志确实插进去了」以及
 * 「原有判定表达式与调用顺序没有被改动」。
 */
internal object DiagSourceText {

    fun source(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path).readText().replace("\r\n", "\n")
    }

    fun assertOrder(source: String, vararg expected: String) {
        var position = -1
        expected.forEach { text ->
            val next = source.indexOf(text)
            assertTrue("Missing or out of order: $text", next > position)
            position = next
        }
    }

    fun countOf(source: String, text: String): Int =
        source.split(text).size - 1

    /** 取两个锚点之间的代码块 */
    fun block(source: String, from: String, to: String): String =
        source.substringAfter(from).substringBefore(to)
}
