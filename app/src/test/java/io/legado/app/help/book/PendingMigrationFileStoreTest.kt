package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 换源身份迁移待重试任务的文件持久化测试（纯 JVM，临时目录）。
 */
class PendingMigrationFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `保存后加载还原为相同任务列表`() {
        val file = tmp.root.resolve("pending.json")
        val store = PendingMigrationFileStore(file)
        val entries = listOf(
            arrayOf("旧名", "作者", "新名", "作者"),
            arrayOf("甲", "乙", "丙", "丁")
        )
        store.save(entries)
        // 新实例读同一文件，模拟进程重启后的重新发现
        val reloaded = PendingMigrationFileStore(file).load()
        assertEquals(entries.map { it.toList() }, reloaded.map { it.toList() })
    }

    @Test
    fun `损坏文件加载为空不抛异常`() {
        val file = tmp.newFile("pending.json")
        file.writeText("{not a json")
        assertTrue(PendingMigrationFileStore(file).load().isEmpty())
    }

    @Test
    fun `文件不存在加载为空`() {
        assertTrue(PendingMigrationFileStore(tmp.root.resolve("absent.json")).load().isEmpty())
    }

    @Test
    fun `清空删除持久化状态`() {
        val file = tmp.newFile("pending.json")
        val store = PendingMigrationFileStore(file)
        store.save(listOf(arrayOf("a", "b", "c", "d")))
        assertTrue(file.exists())
        store.clear()
        assertFalse(file.exists())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `空列表保存后加载为空`() {
        val file = tmp.root.resolve("pending.json")
        val store = PendingMigrationFileStore(file)
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }

}
