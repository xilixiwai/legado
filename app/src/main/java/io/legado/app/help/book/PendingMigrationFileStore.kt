package io.legado.app.help.book

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.io.File

/**
 * 换源身份迁移待重试任务的文件持久化。
 *
 * 只保存换源流程捕获的确定性「旧身份 → 新身份」对（[ReadRecordDetailHelper.migrateBookIdentity]
 * 的入参），不做任何书名/作者相似性推断——重启后的恢复依据是换源当时记录的因果关系，
 * 不是名字相同。数据本体始终保留在数据库旧身份下：文件丢失或损坏只影响自动重试，
 * 不影响数据完整性。
 *
 * 文件格式：JSON 数组，每条为 [oldName, oldAuthor, newName, newAuthor]。
 * 写入为先写临时文件再改名，避免半写文件。所有读写只在 globalExecutor 线程
 * （或测试线程）串行执行。
 */
internal class PendingMigrationFileStore(private val file: File) {

    fun load(): List<Array<String>> {
        if (!file.exists()) return emptyList()
        // 文件损坏视为无待迁移任务：数据仍在数据库旧身份下，可通过再次换源或人工触发恢复
        return runCatching {
            GSON.fromJsonArray<Array<String>>(file.readText()).getOrNull().orEmpty()
        }.getOrElse { emptyList() }
    }

    fun save(entries: List<Array<String>>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(GSON.toJson(entries))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

}
