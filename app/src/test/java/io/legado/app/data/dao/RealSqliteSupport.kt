package io.legado.app.data.dao

import com.google.gson.JsonParser
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 「批量替换域名」真实 SQLite 回归测试的公共支撑。
 *
 * ## 为什么要这层东西
 *
 * Room 生成的 DAO 实现依赖 Android 的 `android.database.sqlite`，JVM 单元测试跑不了。
 * 但历史 bug（`http://m.old.com:8080m.new.com:8080/...`）恰恰只有真实 SQLite 才能暴露，
 * 所以这里的做法是：
 *
 * 1. **SQL 用生产的**：直接引用 [DomainReplaceSql] 里的 `const val`，
 *    与 `@Query` 注解引用的是同一个字符串常量，不是复制一份；
 * 2. **schema 用生产的**：建表语句从 `app/schemas/.../<version>.json` 里读 Room 导出的
 *    真实 DDL（含主键、唯一索引、外键），不手写近似 schema；
 * 3. **绑定语义等价**：Room 会把 `:name` 具名参数按出现顺序换成 `?` 并逐个绑定，
 *    这里做同样的转换（见 [toPositional]），因此执行的 SQL 文本与 Room 实际下发的一致。
 *
 * ## 明确的限制
 *
 * 这**不是**在跑 Room 生成的 DAO 代码，而是用同一个 SQL 字符串 + 同一个生产 schema
 * 在真实 SQLite 引擎上执行。差异只在于「谁来 prepare / bind」：
 * Room 用 `android.database.sqlite.SQLiteStatement`，这里用 JDBC。
 * 两者都是 SQLite，SQL 语义一致；但 `@Query` 的 Room 编译期校验、
 * 以及 Android SQLite 驱动层的行为不在本测试覆盖范围内。
 */
internal object RealSqliteSupport {

    /** 从 Room 导出的 schema 里取最新版本的相关表建表语句（含索引） */
    fun productionDdl(): List<String> {
        val dirs = listOf(
            File("schemas/io.legado.app.data.AppDatabase"),
            File("app/schemas/io.legado.app.data.AppDatabase"),
        )
        val dir = dirs.firstOrNull { it.isDirectory }
            ?: error("找不到 Room schema 目录，已尝试：${dirs.joinToString { it.absolutePath }}")
        val schema = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && f.nameWithoutExtension.toIntOrNull() != null
        }?.maxByOrNull { it.nameWithoutExtension.toInt() }
            ?: error("schema 目录里没有版本化 json：${dir.absolutePath}")

        val root = JsonParser.parseString(schema.readText()).asJsonObject
        val entities = root.getAsJsonObject("database").getAsJsonArray("entities")
        val wanted = listOf("books", "chapters", "bookmarks", "readRecord")
        val found = LinkedHashMap<String, MutableList<String>>()
        entities.forEach { element ->
            val entity = element.asJsonObject
            val table = entity.get("tableName").asString
            if (table !in wanted) return@forEach
            val statements = found.getOrPut(table) { mutableListOf() }
            statements.add(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
            entity.getAsJsonArray("indices")?.forEach { idx ->
                statements.add(
                    idx.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table)
                )
            }
        }
        wanted.forEach { table ->
            check(found.containsKey(table)) { "schema ${schema.name} 里缺少表 $table" }
        }
        return wanted.flatMap { found.getValue(it) }
    }

    /** 内存库 + 生产 schema + 外键开启（与 Room 的默认行为一致） */
    fun open(): Connection {
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            productionDdl().forEach { statement.execute(it) }
        }
        return connection
    }

    /**
     * 把 Room 的具名参数 `:name` 换成 `?`，并按出现顺序记录每个 `?` 对应的参数名。
     *
     * 与 Room 生成代码的绑定方式一致：每出现一次就多一个 `?`、多一次绑定。
     * 字符串字面量里的 `:`（例如 `'http://new.com:8080%'`）不是绑定参数，必须跳过，
     * 否则会把端口当成参数名。
     */
    fun toPositional(sql: String, args: Map<String, String>): Pair<String, List<String>> {
        val order = mutableListOf<String>()
        val out = StringBuilder(sql.length + 16)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    out.append('\'')
                    i++
                    while (i < sql.length) {
                        if (sql[i] == '\'') {
                            if (i + 1 < sql.length && sql[i + 1] == '\'') {
                                out.append("''")
                                i += 2
                                continue
                            }
                            out.append('\'')
                            i++
                            break
                        }
                        out.append(sql[i])
                        i++
                    }
                }

                c == ':' && i + 1 < sql.length &&
                        (sql[i + 1].isLetter() || sql[i + 1] == '_') -> {
                    var end = i + 1
                    while (end < sql.length &&
                        (sql[end].isLetterOrDigit() || sql[end] == '_')
                    ) {
                        end++
                    }
                    val name = sql.substring(i + 1, end)
                    require(args.containsKey(name)) { "SQL 里的绑定参数 :$name 没有提供值" }
                    order.add(name)
                    out.append('?')
                    i = end
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString() to order
    }
}

// ---------------------------------------------------------------------- Connection 扩展

private fun Connection.bind(statement: java.sql.PreparedStatement, order: List<String>, args: Map<String, String>) {
    order.forEachIndexed { index, name ->
        statement.setString(index + 1, args.getValue(name))
    }
}

/** 执行一条写语句，返回影响行数（等价于 Room 的 `UPDATE` 返回 Int） */
internal fun Connection.exec(sql: String, args: Map<String, String> = emptyMap()): Int {
    val (positional, order) = RealSqliteSupport.toPositional(sql, args)
    prepareStatement(positional).use { statement ->
        bind(statement, order, args)
        return statement.executeUpdate()
    }
}

/** 执行不带绑定参数的语句（PRAGMA 等） */
internal fun Connection.execRaw(sql: String) {
    createStatement().use { it.execute(sql) }
}

/** 执行一条返回 Int 的查询 */
internal fun Connection.scalar(sql: String, args: Map<String, String> = emptyMap()): Int {
    val (positional, order) = RealSqliteSupport.toPositional(sql, args)
    prepareStatement(positional).use { statement ->
        bind(statement, order, args)
        statement.executeQuery().use { rs ->
            return if (rs.next()) rs.getInt(1) else 0
        }
    }
}

/** 执行一条返回单列的查询 */
internal fun Connection.strings(sql: String, args: Map<String, String> = emptyMap()): List<String> {
    val (positional, order) = RealSqliteSupport.toPositional(sql, args)
    prepareStatement(positional).use { statement ->
        bind(statement, order, args)
        statement.executeQuery().use { rs ->
            val out = mutableListOf<String>()
            while (rs.next()) out.add(rs.getString(1))
            return out
        }
    }
}

/** 执行 [DomainReplaceSql] 里的分组查询（无绑定参数） */
internal fun Connection.groups(sql: String): List<DomainGroupRow> {
    prepareStatement(sql).use { statement ->
        statement.executeQuery().use { rs ->
            val out = mutableListOf<DomainGroupRow>()
            while (rs.next()) {
                out.add(DomainGroupRow(rs.getString(1), rs.getString(2), rs.getInt(3)))
            }
            return out
        }
    }
}

/** 外键自检：返回悬空引用的描述，空表示外键完整 */
internal fun Connection.foreignKeyViolations(): List<String> {
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_check").use { rs ->
            val out = mutableListOf<String>()
            while (rs.next()) {
                out.add("${rs.getString(1)} rowid=${rs.getString(2)} -> ${rs.getString(3)}")
            }
            return out
        }
    }
}

/** 插入一本书；参数是列名到值的映射，未给出的列走 schema 默认值 */
internal fun Connection.insertBook(values: Map<String, String>) = insertRow("books", values)

/** 插入一章 */
internal fun Connection.insertChapter(values: Map<String, String>) = insertRow("chapters", values)

private fun Connection.insertRow(table: String, values: Map<String, String>) {
    val columns = values.keys.joinToString(", ") { "`$it`" }
    val holders = values.keys.joinToString(", ") { "?" }
    prepareStatement("INSERT INTO $table ($columns) VALUES ($holders)").use { statement ->
        values.values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeUpdate()
    }
}
