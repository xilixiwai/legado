package io.legado.app.data.dao

/**
 * 「批量替换域名」用到的全部 SQL。
 *
 * 存在的理由：JVM 单元测试跑不了 Room 生成的 DAO（Android SQLite 不可用），
 * 但**必须**验证真实 SQL 的行为（历史上出过 authority 拼接错误）。把 SQL 提成 `const val`，
 * 让 `@Query` 与单元测试引用**同一份字符串**，测试才真正覆盖生产 SQL，而不是复制一份。
 *
 * 约定：
 * - `:prefix`  = `scheme://authority`（原样前缀）
 * - `:newPrefix` = `scheme://newAuthority`（替换后的前缀，由 Kotlin 侧算好，SQL 不做任何字符串推断）
 * - 所有参数都是 Room 绑定参数，用户输入从不拼进 SQL。
 */
object DomainReplaceSql {

    /**
     * 按 (scheme, authority) 分组统计 `chapters.url` 的命中分布。
     *
     * authority = `scheme://` 之后、第一个 `/` `?` `#` 或规则参数块起始 `,{` 之前的部分
     * （含 userinfo 与端口）。实现上先把这些分隔符临时换成 `/`，再取第一个 `/` 的下标，
     * 避免依赖正则或不可移植函数。
     */
    const val CHAPTER_URL_GROUPS = """
        SELECT scheme, authority, COUNT(*) AS cnt FROM (
            SELECT
                CASE WHEN url LIKE 'https://%' THEN substr(url, 1, 8) ELSE substr(url, 1, 7) END AS scheme,
                substr(
                    substr(url, CASE WHEN url LIKE 'https://%' THEN 9 ELSE 8 END),
                    1,
                    instr(
                        replace(replace(replace(substr(url, CASE WHEN url LIKE 'https://%' THEN 9 ELSE 8 END), '?', '/'), '#', '/'), ',{', '/{') || '/',
                        '/'
                    ) - 1
                ) AS authority
            FROM chapters
            WHERE url LIKE 'http://%' OR url LIKE 'https://%'
        )
        GROUP BY scheme, authority
        """

    /** 同 [CHAPTER_URL_GROUPS]，作用于 `chapters.baseUrl` */
    const val CHAPTER_BASE_URL_GROUPS = """
        SELECT scheme, authority, COUNT(*) AS cnt FROM (
            SELECT
                CASE WHEN baseUrl LIKE 'https://%' THEN substr(baseUrl, 1, 8) ELSE substr(baseUrl, 1, 7) END AS scheme,
                substr(
                    substr(baseUrl, CASE WHEN baseUrl LIKE 'https://%' THEN 9 ELSE 8 END),
                    1,
                    instr(
                        replace(replace(replace(substr(baseUrl, CASE WHEN baseUrl LIKE 'https://%' THEN 9 ELSE 8 END), '?', '/'), '#', '/'), ',{', '/{') || '/',
                        '/'
                    ) - 1
                ) AS authority
            FROM chapters
            WHERE baseUrl LIKE 'http://%' OR baseUrl LIKE 'https://%'
        )
        GROUP BY scheme, authority
        """

    /** 同 [CHAPTER_URL_GROUPS]，作用于 `chapters.resourceUrl` */
    const val CHAPTER_RESOURCE_URL_GROUPS = """
        SELECT scheme, authority, COUNT(*) AS cnt FROM (
            SELECT
                CASE WHEN resourceUrl LIKE 'https://%' THEN substr(resourceUrl, 1, 8) ELSE substr(resourceUrl, 1, 7) END AS scheme,
                substr(
                    substr(resourceUrl, CASE WHEN resourceUrl LIKE 'https://%' THEN 9 ELSE 8 END),
                    1,
                    instr(
                        replace(replace(replace(substr(resourceUrl, CASE WHEN resourceUrl LIKE 'https://%' THEN 9 ELSE 8 END), '?', '/'), '#', '/'), ',{', '/{') || '/',
                        '/'
                    ) - 1
                ) AS authority
            FROM chapters
            WHERE resourceUrl LIKE 'http://%' OR resourceUrl LIKE 'https://%'
        )
        GROUP BY scheme, authority
        """

    /**
     * 命中条件：前 `length(prefix)` 个字符等于 `:prefix`，且其后紧跟 `/`、`?`、`#`、
     * 规则参数块起始的 `,{`，或字符串结束。
     *
     * 这正是「authority 完全相等」的判定，与分组条件严格等价，
     * 因此**预览数量与实际更新数量必然一致**。
     *
     * 替换方式：整段前缀换成 `:newPrefix`，再拼回旧地址的剩余部分。
     * 绝不能写成 `substr(url,1,length(:prefix)) || :newAuthority || ...`——
     * 那会把旧 authority 留在原地，拼出 `http://m.old.com:8080m.new.com:8080/...` 这种坏 URL。
     */
    const val REPLACE_CHAPTER_URL_DOMAIN = """
        UPDATE chapters
        SET url = :newPrefix || substr(url, length(:prefix) + 1)
        WHERE substr(url, 1, length(:prefix)) = :prefix
          AND (length(url) = length(:prefix)
              OR substr(url, length(:prefix) + 1, 1) IN ('/', '?', '#')
              OR (substr(url, length(:prefix) + 1, 1) = ',' AND substr(url, length(:prefix) + 2, 1) = '{'))
        """

    /** 同 [REPLACE_CHAPTER_URL_DOMAIN]，作用于 `chapters.baseUrl` */
    const val REPLACE_CHAPTER_BASE_URL_DOMAIN = """
        UPDATE chapters
        SET baseUrl = :newPrefix || substr(baseUrl, length(:prefix) + 1)
        WHERE substr(baseUrl, 1, length(:prefix)) = :prefix
          AND (length(baseUrl) = length(:prefix)
              OR substr(baseUrl, length(:prefix) + 1, 1) IN ('/', '?', '#')
              OR (substr(baseUrl, length(:prefix) + 1, 1) = ',' AND substr(baseUrl, length(:prefix) + 2, 1) = '{'))
        """

    /** 同 [REPLACE_CHAPTER_URL_DOMAIN]，作用于 `chapters.resourceUrl` */
    const val REPLACE_CHAPTER_RESOURCE_URL_DOMAIN = """
        UPDATE chapters
        SET resourceUrl = :newPrefix || substr(resourceUrl, length(:prefix) + 1)
        WHERE substr(resourceUrl, 1, length(:prefix)) = :prefix
          AND (length(resourceUrl) = length(:prefix)
              OR substr(resourceUrl, length(:prefix) + 1, 1) IN ('/', '?', '#')
              OR (substr(resourceUrl, length(:prefix) + 1, 1) = ',' AND substr(resourceUrl, length(:prefix) + 2, 1) = '{'))
        """

    /**
     * 更新前的主键冲突预检。
     *
     * `chapters` 主键是 `(url, bookUrl)`。改写 url 后可能与**同一本书里已经存在于新域名的记录**撞主键。
     * 返回大于 0 表示存在冲突，调用方必须放弃本次替换。
     */
    const val COUNT_CHAPTER_URL_CONFLICTS = """
        SELECT COUNT(*) FROM chapters a
        WHERE substr(a.url, 1, length(:prefix)) = :prefix
          AND (length(a.url) = length(:prefix)
            OR substr(a.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
            OR (substr(a.url, length(:prefix) + 1, 1) = ',' AND substr(a.url, length(:prefix) + 2, 1) = '{'))
          AND EXISTS (
              SELECT 1 FROM chapters b
              WHERE b.bookUrl = a.bookUrl
                AND b.url = :newPrefix || substr(a.url, length(:prefix) + 1)
          )
        """

    /**
     * 单本书的章节主键冲突预检（冲突清单「重新检查 / 单本完成替换」用）。
     */
    const val COUNT_CHAPTER_URL_CONFLICTS_FOR_BOOK = """
        SELECT COUNT(*) FROM chapters a
        WHERE a.bookUrl = :bookUrl
          AND substr(a.url, 1, length(:prefix)) = :prefix
          AND (length(a.url) = length(:prefix)
            OR substr(a.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
            OR (substr(a.url, length(:prefix) + 1, 1) = ',' AND substr(a.url, length(:prefix) + 2, 1) = '{'))
          AND EXISTS (
              SELECT 1 FROM chapters b
              WHERE b.bookUrl = a.bookUrl
                AND b.url = :newPrefix || substr(a.url, length(:prefix) + 1)
          )
        """

    /**
     * 章节主键冲突的自动处理：同一本书里同一章节存在新旧域名两行时，删除 `index` 较大的一行。
     *
     * 业务含义：`(url, bookUrl)` 主键冲突的唯一成因是同书内同一章节路径同时存在
     * 旧域名行与新域名行（部分迁移状态），改写后两行 url 相同，唯一约束只能保留一行。
     * 保留 `index` 较小的一行：目录顺序与阅读位置保持连续，无需重排；
     * 删除行数单独计数并在结果中报告。
     *
     * 两个分支分别从冲突对的两侧识别同一批冲突对，`index` 唯一索引保证每对恰好删一行：
     * - 分支一：本行是待改写的旧域名行，已存在改写后同 URL 的行且其 `index` 更小；
     * - 分支二：本行是新域名行，存在将改写成同 URL 的旧域名行（边界合法）且其 `index` 更小。
     */
    const val DELETE_DUPLICATE_CHAPTER_URLS = """
        DELETE FROM chapters
        WHERE (
                substr(chapters.url, 1, length(:prefix)) = :prefix
                AND (length(chapters.url) = length(:prefix)
                  OR substr(chapters.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                  OR (substr(chapters.url, length(:prefix) + 1, 1) = ',' AND substr(chapters.url, length(:prefix) + 2, 1) = '{'))
              )
              AND EXISTS (
                  SELECT 1 FROM chapters b
                  WHERE b.bookUrl = chapters.bookUrl
                    AND b.url = :newPrefix || substr(chapters.url, length(:prefix) + 1)
                    AND b.`index` < chapters.`index`
              )
            OR (
                substr(chapters.url, 1, length(:newPrefix)) = :newPrefix
                AND EXISTS (
                    SELECT 1 FROM chapters b
                    WHERE b.bookUrl = chapters.bookUrl
                      AND substr(b.url, 1, length(:prefix)) = :prefix
                      AND (length(b.url) = length(:prefix)
                        OR substr(b.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                        OR (substr(b.url, length(:prefix) + 1, 1) = ',' AND substr(b.url, length(:prefix) + 2, 1) = '{'))
                      AND b.url = :prefix || substr(chapters.url, length(:newPrefix) + 1)
                      AND b.`index` < chapters.`index`
                )
            )
        """

    /** 单本书范围的重复章节删除（单本完成替换用），语义同 [DELETE_DUPLICATE_CHAPTER_URLS] */
    const val DELETE_DUPLICATE_CHAPTER_URLS_FOR_BOOK = """
        DELETE FROM chapters
        WHERE bookUrl = :bookUrl
          AND (
                (
                    substr(chapters.url, 1, length(:prefix)) = :prefix
                    AND (length(chapters.url) = length(:prefix)
                      OR substr(chapters.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                      OR (substr(chapters.url, length(:prefix) + 1, 1) = ',' AND substr(chapters.url, length(:prefix) + 2, 1) = '{'))
                )
                AND EXISTS (
                    SELECT 1 FROM chapters b
                    WHERE b.bookUrl = chapters.bookUrl
                      AND b.url = :newPrefix || substr(chapters.url, length(:prefix) + 1)
                      AND b.`index` < chapters.`index`
                )
              OR (
                    substr(chapters.url, 1, length(:newPrefix)) = :newPrefix
                    AND EXISTS (
                        SELECT 1 FROM chapters b
                        WHERE b.bookUrl = chapters.bookUrl
                          AND substr(b.url, 1, length(:prefix)) = :prefix
                          AND (length(b.url) = length(:prefix)
                            OR substr(b.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                            OR (substr(b.url, length(:prefix) + 1, 1) = ',' AND substr(b.url, length(:prefix) + 2, 1) = '{'))
                          AND b.url = :prefix || substr(chapters.url, length(:newPrefix) + 1)
                          AND b.`index` < chapters.`index`
                    )
                )
          )
        """

    /** 列出存在章节主键冲突的书籍（阅读进度修正按书进行） */
    const val CONFLICT_CHAPTER_BOOKS = """
        SELECT DISTINCT a.bookUrl FROM chapters a
        WHERE (
                substr(a.url, 1, length(:prefix)) = :prefix
                AND (length(a.url) = length(:prefix)
                  OR substr(a.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                  OR (substr(a.url, length(:prefix) + 1, 1) = ',' AND substr(a.url, length(:prefix) + 2, 1) = '{'))
              )
              AND EXISTS (
                  SELECT 1 FROM chapters b
                  WHERE b.bookUrl = a.bookUrl
                    AND b.url = :newPrefix || substr(a.url, length(:prefix) + 1)
                    AND b.`index` < a.`index`
              )
            OR (
                substr(a.url, 1, length(:newPrefix)) = :newPrefix
                AND EXISTS (
                    SELECT 1 FROM chapters b
                    WHERE b.bookUrl = a.bookUrl
                      AND substr(b.url, 1, length(:prefix)) = :prefix
                      AND (length(b.url) = length(:prefix)
                        OR substr(b.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                        OR (substr(b.url, length(:prefix) + 1, 1) = ',' AND substr(b.url, length(:prefix) + 2, 1) = '{'))
                      AND b.url = :prefix || substr(a.url, length(:newPrefix) + 1)
                      AND b.`index` < a.`index`
                )
            )
        """

    /** 列出单本书内将被删除的重复章节 url（阅读进度修正的快照依据），语义同 [DELETE_DUPLICATE_CHAPTER_URLS] */
    const val DUPLICATE_CHAPTER_URLS_FOR_BOOK = """
        SELECT url FROM chapters a
        WHERE a.bookUrl = :bookUrl
          AND (
                (
                    substr(a.url, 1, length(:prefix)) = :prefix
                    AND (length(a.url) = length(:prefix)
                      OR substr(a.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                      OR (substr(a.url, length(:prefix) + 1, 1) = ',' AND substr(a.url, length(:prefix) + 2, 1) = '{'))
                )
                AND EXISTS (
                    SELECT 1 FROM chapters b
                    WHERE b.bookUrl = a.bookUrl
                      AND b.url = :newPrefix || substr(a.url, length(:prefix) + 1)
                      AND b.`index` < a.`index`
                )
              OR (
                    substr(a.url, 1, length(:newPrefix)) = :newPrefix
                    AND EXISTS (
                        SELECT 1 FROM chapters b
                        WHERE b.bookUrl = a.bookUrl
                          AND substr(b.url, 1, length(:prefix)) = :prefix
                          AND (length(b.url) = length(:prefix)
                            OR substr(b.url, length(:prefix) + 1, 1) IN ('/', '?', '#')
                            OR (substr(b.url, length(:prefix) + 1, 1) = ',' AND substr(b.url, length(:prefix) + 2, 1) = '{'))
                          AND b.url = :prefix || substr(a.url, length(:newPrefix) + 1)
                          AND b.`index` < a.`index`
                    )
                )
          )
          ORDER BY `index`
        """

    /** 同 [REPLACE_CHAPTER_URL_DOMAIN]，额外限定单本书（冲突消失后单本完成替换用） */
    const val REPLACE_CHAPTER_URL_DOMAIN_FOR_BOOK = """
        UPDATE chapters
        SET url = :newPrefix || substr(url, length(:prefix) + 1)
        WHERE bookUrl = :bookUrl
          AND substr(url, 1, length(:prefix)) = :prefix
          AND (length(url) = length(:prefix)
              OR substr(url, length(:prefix) + 1, 1) IN ('/', '?', '#')
              OR (substr(url, length(:prefix) + 1, 1) = ',' AND substr(url, length(:prefix) + 2, 1) = '{'))
        """

    /** 同上，作用于 baseUrl */
    const val REPLACE_CHAPTER_BASE_URL_DOMAIN_FOR_BOOK = """
        UPDATE chapters
        SET baseUrl = :newPrefix || substr(baseUrl, length(:prefix) + 1)
        WHERE bookUrl = :bookUrl
          AND substr(baseUrl, 1, length(:prefix)) = :prefix
          AND (length(baseUrl) = length(:prefix)
              OR substr(baseUrl, length(:prefix) + 1, 1) IN ('/', '?', '#')
              OR (substr(baseUrl, length(:prefix) + 1, 1) = ',' AND substr(baseUrl, length(:prefix) + 2, 1) = '{'))
        """

    /** 同上，作用于 resourceUrl */
    const val REPLACE_CHAPTER_RESOURCE_URL_DOMAIN_FOR_BOOK = """
        UPDATE chapters
        SET resourceUrl = :newPrefix || substr(resourceUrl, length(:prefix) + 1)
        WHERE bookUrl = :bookUrl
          AND substr(resourceUrl, 1, length(:prefix)) = :prefix
          AND (length(resourceUrl) = length(:prefix)
              OR substr(resourceUrl, length(:prefix) + 1, 1) IN ('/', '?', '#')
              OR (substr(resourceUrl, length(:prefix) + 1, 1) = ',' AND substr(resourceUrl, length(:prefix) + 2, 1) = '{'))
        """

    /**
     * 同步章节归属（FK: `chapters.bookUrl` → `books.bookUrl`）。
     * 只改 bookUrl 不改 url，不会触发 `(url, bookUrl)` 主键冲突。
     */
    const val REPLACE_CHAPTER_BOOK_URL =
        "update chapters set bookUrl = :newBookUrl where bookUrl = :oldBookUrl"

    /**
     * 旧书源记录清理时把书签挂到保留记录名下（按书名+作者整体改写；bookmarks 无主键冲突）。
     */
    const val REKEY_BOOKMARKS =
        "update bookmarks set bookName = :newName, bookAuthor = :newAuthor " +
                "where bookName = :oldName and bookAuthor = :oldAuthor"

    /**
     * 旧书源记录清理时迁移阅读时长统计。
     * readRecord 主键是 (deviceId, bookName)：与保留记录的行撞键时跳过（OR IGNORE）。
     */
    const val REKEY_READ_RECORD =
        "update or ignore readRecord set bookName = :newName where bookName = :oldName"

    /**
     * 更新书籍自身的网络地址。
     *
     * `bookUrl` 是主键，与 `tocUrl` 必须在同一条语句内更新，
     * 避免出现「bookUrl 已换、tocUrl 还指向旧域名」的中间态。
     * origin / originName / originOrder 一律不动。
     */
    const val UPDATE_BOOK_URLS = """
        UPDATE books
        SET bookUrl = :newBookUrl, tocUrl = :newTocUrl
        WHERE bookUrl = :oldBookUrl
        """
}
