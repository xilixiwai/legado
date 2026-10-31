package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 漫画缓存候选「单次解析 + 校验」的行为测试（[MangaImageContent.firstValidImageFile]）。
 *
 * 校验器用注入的假实现模拟「按内容可解码」：真实 BitmapFactory 解码属 Android 运行时，
 * 不在 JVM 测试边界内；这里保护的是候选顺序、损坏候选不阻断有效候选、
 * 旧命名兜底校验与「命中即短路」的单次遍历语义。
 */
class MangaImageResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = MangaImageKeys.baseName("https://cdn.test/p/001.jpg")

    /** 可校验文件写入伪内容；校验通过与否由 validNames 决定，调用次数全程记录 */
    private inner class CountingValidator(vararg validNames: String) {
        val valid = validNames.toSet()
        val calls = mutableListOf<String>()

        fun validate(file: File): Boolean {
            calls.add(file.name)
            return file.name in valid
        }
    }

    @Test
    fun `首个候选有效时一次校验即短路返回`() {
        val dir = tmp.newFolder()
        val mcache = File(dir, "$base.mcache").apply { writeText("good") }
        val validator = CountingValidator("$base.mcache")

        val result = MangaImageContent.firstValidImageFile(
            dir, base, "jpg", "$base.jpg", validator::validate
        )

        assertEquals(mcache, result)
        assertEquals(listOf("$base.mcache"), validator.calls)
    }

    @Test
    fun `损坏的mcache不阻断有效旧候选`() {
        val dir = tmp.newFolder()
        //损坏的 .mcache 在盘上（校验不通过），有效 .jpg 旧候选必须仍可命中
        File(dir, "$base.mcache").writeText("corrupt")
        val jpg = File(dir, "$base.jpg").apply { writeText("good") }
        val validator = CountingValidator("$base.jpg")

        val result = MangaImageContent.firstValidImageFile(
            dir, base, "jpg", "$base.jpg", validator::validate
        )

        assertEquals(jpg, result)
        //损坏候选只多消耗一次校验，不阻断也不误删（缺失的 .svg 候选也会被校验一次）
        assertEquals(listOf("$base.mcache", "$base.svg", "$base.jpg"), validator.calls)
    }

    @Test
    fun `全部候选无效但旧命名文件有效时兜底命中`() {
        val dir = tmp.newFolder()
        File(dir, "$base.mcache").writeText("corrupt")
        //旧命名兜底文件不在候选列表里也能被校验（保持与 getImage 兜底一致）
        val legacy = File(dir, "$base.jpeg").apply { writeText("good") }
        val validator = CountingValidator("$base.jpeg")

        val result = MangaImageContent.firstValidImageFile(
            dir, base, "jpg", "$base.jpeg", validator::validate
        )

        assertEquals(legacy, result)
        assertEquals("$base.jpeg", validator.calls.last())
    }

    @Test
    fun `全部无效时返回null且不遗漏旧命名兜底校验`() {
        val dir = tmp.newFolder()
        val validator = CountingValidator()

        val result = MangaImageContent.firstValidImageFile(
            dir, base, "jpg", "$base.jpg", validator::validate
        )

        assertNull(result)
        //候选全查完，旧命名兜底也被校验过
        assertTrue(validator.calls.contains("$base.jpg"))
        assertEquals(
            MangaImageContent.candidatesFor(base, "jpg").size + 1,
            validator.calls.size
        )
    }

    @Test
    fun `校验器抛异常按无效处理不中断候选查找`() {
        val dir = tmp.newFolder()
        File(dir, "$base.mcache").writeText("boom")
        val jpg = File(dir, "$base.jpg").apply { writeText("good") }
        val checked = mutableListOf<String>()

        val result = MangaImageContent.firstValidImageFile(
            dir, base, "jpg", "$base.jpg"
        ) { file ->
            checked.add(file.name)
            if (file.name == "$base.mcache") throw IllegalStateException("读取失败")
            file.name == "$base.jpg"
        }

        assertEquals(jpg, result)
        assertTrue(checked.contains("$base.jpg"))
    }

    @Test
    fun `候选顺序为新后缀svg旧后缀小写与常见后缀`() {
        val dir = tmp.newFolder()
        val validator = CountingValidator()

        MangaImageContent.firstValidImageFile(
            dir, base, "JPG", "$base.JPG", validator::validate
        )

        assertEquals(
            listOf(
                "$base.mcache", "$base.svg", "$base.JPG", "$base.jpg",
                "$base.jpeg", "$base.png", "$base.webp", "$base.gif", "$base.bmp",
                //全部候选无效后，旧命名兜底文件也会被校验一次
                "$base.JPG",
            ),
            validator.calls,
        )
    }
}
