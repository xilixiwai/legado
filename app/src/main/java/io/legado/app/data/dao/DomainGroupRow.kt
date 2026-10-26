package io.legado.app.data.dao

/**
 * 「批量替换域名」的域名分组行。
 *
 * - [scheme]：URL 的协议前缀，形如 `https://` / `http://`（保留原始大小写）
 * - [authority]：`scheme://` 之后、第一个 `/` `?` `#` 之前的部分，形如 `m.old.com` / `old.com:8080`
 * - [cnt]：该分组下的记录数
 *
 * 一个库里通常只有个位数到几十个分组，因此可以把分组结果整体取到内存里判定，
 * 而不必把成千上万条章节逐条加载。
 */
data class DomainGroupRow(
    val scheme: String,
    val authority: String,
    val cnt: Int,
)
