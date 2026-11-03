package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.DetailBookRankShow
import io.legado.app.data.entities.ReadRecordDetail

@Dao
interface ReadRecordDetailDao {

    @Insert
    fun insert(vararg rows: ReadRecordDetail)

    @Update
    fun update(vararg rows: ReadRecordDetail)

    @get:Query("select * from readRecordDetail")
    val all: List<ReadRecordDetail>

    @Query(
        """select * from readRecordDetail where deviceId = :deviceId
            and bookName = :bookName and bookAuthor = :bookAuthor and date = :date"""
    )
    fun get(deviceId: String, bookName: String, bookAuthor: String, date: String): ReadRecordDetail?

    @Query("select * from readRecordDetail where date >= :start and date < :end")
    fun getByDateRange(start: String, end: String): List<ReadRecordDetail>

    @Query("select sum(readTime) from readRecordDetail where date >= :start and date < :end")
    fun sumRange(start: String, end: String): Long?

    @Query(
        """select bookName, bookAuthor, max(bookType) as bookType, sum(readTime) as readTime,
            max(lastReadTime) as lastReadTime
            from readRecordDetail
            where date >= :start and date < :end
            group by bookName, bookAuthor
            order by readTime desc"""
    )
    fun rankRange(start: String, end: String): List<DetailBookRankShow>

    @Query("select min(date) from readRecordDetail")
    fun firstDate(): String?

    @Query("delete from readRecordDetail")
    fun clear()

    @Query("delete from readRecordDetail where bookName = :bookName")
    fun deleteByName(bookName: String)
}
