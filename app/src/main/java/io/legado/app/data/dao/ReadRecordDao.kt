package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow

@Dao
interface ReadRecordDao {

    @get:Query("select * from readRecord")
    val all: List<ReadRecord>

    @get:Query(
        """
        select bookName, sum(readTime) as readTime, max(lastRead) as lastRead 
        from readRecord 
        group by bookName 
        order by bookName collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select sum(readTime) from readRecord")
    val allTime: Long

    @Query(
        """
        select bookName, sum(readTime) as readTime, max(lastRead) as lastRead 
        from readRecord 
        where bookName like '%' || :searchKey || '%'
        group by bookName 
        order by bookName collate localized"""
    )
    fun search(searchKey: String): List<ReadRecordShow>

    @Query("select sum(readTime) from readRecord where bookName = :bookName")
    fun getReadTime(bookName: String): Long?

    @Query("select readTime from readRecord where deviceId = :androidId and bookName = :bookName")
    fun getReadTime(androidId: String, bookName: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg readRecord: ReadRecord)

    @Update
    fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where bookName = :bookName")
    fun deleteByName(bookName: String)

    /**
     * 「批量替换域名」旧书源记录清理时迁移阅读时长统计。
     * 主键是 (deviceId, bookName)：与保留记录的统计行撞键时跳过该行（OR IGNORE），返回迁移行数。
     */
    @Query(DomainReplaceSql.REKEY_READ_RECORD)
    fun rekeyOwner(oldName: String, newName: String): Int
}