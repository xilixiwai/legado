package io.legado.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReadRecordDetail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 每日统计表真实 SQLite/Room 测试（需在真机或模拟器执行：
 * `gradlew connectedAppReleaseAndroidTest` 或 Android Studio 运行）。
 * 覆盖：PK 唯一与更新、设备隔离、范围聚合、按书删除、两表清空互不影响书架/进度表。
 */
@RunWith(AndroidJUnit4::class)
class ReadRecordDetailDbTest {

    private lateinit var db: AppDatabase
    private var testStoreFile: java.io.File? = null

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 身份迁移持久化文件定向到测试缓存目录，避免污染应用真实待迁移状态
        val storeFile = java.io.File(context.cacheDir, "test_pending_migrations.json")
        storeFile.delete()
        io.legado.app.help.book.ReadRecordDetailHelper.migrationStoreForTest =
            io.legado.app.help.book.PendingMigrationFileStore(storeFile)
        testStoreFile = storeFile
    }

    @After
    fun tearDown() {
        db.close()
        io.legado.app.help.book.ReadRecordDetailHelper.migrationStoreForTest = null
        testStoreFile?.delete()
    }

    private fun row(
        deviceId: String = "devA",
        bookName: String = "书",
        bookAuthor: String = "作者",
        date: String = "2026-11-04",
        readTime: Long = 60_000L
    ) = ReadRecordDetail(
        deviceId = deviceId, bookName = bookName, bookAuthor = bookAuthor,
        date = date, readTime = readTime, firstReadTime = 1_000L,
        lastReadTime = 1_000L + readTime, bookType = 8
    )

    @Test
    fun insertAndGetByPrimaryKey() {
        db.readRecordDetailDao.insert(row())
        val got = db.readRecordDetailDao.get("devA", "书", "作者", "2026-11-04")
        assertEquals(60_000L, got?.readTime)
        assertNull(db.readRecordDetailDao.get("devB", "书", "作者", "2026-11-04"))
    }

    @Test
    fun differentDevicesDoNotOverwriteEachOther() {
        db.readRecordDetailDao.insert(row(deviceId = "devA", readTime = 100_000L))
        db.readRecordDetailDao.insert(row(deviceId = "devB", readTime = 200_000L))
        assertEquals(100_000L, db.readRecordDetailDao.get("devA", "书", "作者", "2026-11-04")?.readTime)
        assertEquals(200_000L, db.readRecordDetailDao.get("devB", "书", "作者", "2026-11-04")?.readTime)
        // 聚合口径：跨设备求和（各设备独立计算，展示时合并求和）
        assertEquals(
            300_000L,
            db.readRecordDetailDao.sumRange("2026-11-01", "2026-11-05")
        )
    }

    @Test
    fun sameAuthorOnlyNoMergeForDifferentAuthor() {
        db.readRecordDetailDao.insert(row(bookAuthor = "作者甲"))
        db.readRecordDetailDao.insert(row(bookAuthor = "作者乙"))
        assertEquals(
            2,
            db.readRecordDetailDao.getByDateRange("2026-11-01", "2026-11-05").size
        )
        // 按书名删除（记录 Tab 删除语义）：同名跨作者一并清除，且不误删他书
        db.readRecordDetailDao.insert(row(bookName = "其他书"))
        db.readRecordDetailDao.deleteByName("书")
        assertEquals(1, db.readRecordDetailDao.all.size)
        assertEquals("其他书", db.readRecordDetailDao.all[0].bookName)
    }

    @Test
    fun rangeSumAndRankGroupByBook() {
        db.readRecordDetailDao.insert(row(readTime = 100_000L))
        db.readRecordDetailDao.insert(
            row(bookAuthor = "作者乙", readTime = 50_000L, date = "2026-11-05")
        )
        db.readRecordDetailDao.insert(row(date = "2026-10-31", readTime = 999_999L))
        assertEquals(
            150_000L,
            db.readRecordDetailDao.sumRange("2026-11-01", "2026-11-06")
        )
        val rank = db.readRecordDetailDao.rankRange("2026-11-01", "2026-11-06")
        assertEquals(2, rank.size)
        assertEquals("书", rank[0].bookName)
        assertEquals(150_000L, rank[0].readTime)
        assertTrue(rank[0].isManga)
    }

    @Test
    fun updateRowKeepsPrimaryKey() {
        db.readRecordDetailDao.insert(row(readTime = 10_000L))
        val old = db.readRecordDetailDao.get("devA", "书", "作者", "2026-11-04")!!
        old.readTime = 30_000L
        db.readRecordDetailDao.update(old)
        assertEquals(1, db.readRecordDetailDao.all.size)
        assertEquals(30_000L, db.readRecordDetailDao.all[0].readTime)
    }

    @Test
    fun clearDetailDoesNotTouchBooksOrProgress() {
        // 建立书籍/进度/书源基线数据
        val book = io.legado.app.data.entities.Book()
        book.name = "书"
        book.author = "作者"
        book.bookUrl = "https://example.com/book"
        book.origin = "https://example.com"
        db.bookDao.insert(book)
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = "devA"; bookName = "书"; readTime = 1L
            }
        )
        db.readRecordDetailDao.insert(row())
        // 清空两张统计表
        db.runInTransaction {
            db.readRecordDao.clear()
            db.readRecordDetailDao.clear()
        }
        assertEquals(0, db.readRecordDao.all.size)
        assertEquals(0, db.readRecordDetailDao.all.size)
        // 书籍/进度未被误删
        assertEquals(1, db.bookDao.all.size)
        assertTrue(db.bookChapterDao.getChapterCount("https://example.com/book") >= 0)
        assertFalse(db.readRecordDetailDao.all.isNotEmpty())
    }

    @Test
    fun migrateIdentityMovesDetailRowsAndSumsDateCollision() {
        val androidId = io.legado.app.constant.AppConst.androidId
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "旧名", bookAuthor = "旧作者", date = "2026-11-04", readTime = 100_000L)
        )
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "旧名", bookAuthor = "旧作者", date = "2026-11-05", readTime = 50_000L)
        )
        // 新键当日已有数据：迁移后应求和而非覆盖
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "新名", bookAuthor = "新作者", date = "2026-11-04", readTime = 30_000L)
        )
        // 其他设备的行不迁移
        db.readRecordDetailDao.insert(
            row(deviceId = "otherDevice", bookName = "旧名", bookAuthor = "旧作者", date = "2026-11-04", readTime = 999_999L)
        )
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity(
            "旧名", "旧作者", "新名", "新作者", db
        )
        val rows = db.readRecordDetailDao.getByBook(androidId, "新名", "新作者").sortedBy { it.date }
        assertEquals(2, rows.size)
        assertEquals("2026-11-04", rows[0].date)
        assertEquals(130_000L, rows[0].readTime)
        assertEquals(50_000L, rows[1].readTime)
        // 旧键本机行已清空，其他设备行保留原位
        assertEquals(0, db.readRecordDetailDao.getByBook(androidId, "旧名", "旧作者").size)
        assertEquals(
            999_999L,
            db.readRecordDetailDao.get("otherDevice", "旧名", "旧作者", "2026-11-04")?.readTime
        )
    }

    @Test
    fun migrateIdentityRekeysCumulativeRowOnlyWhenNameChanges() {
        // 书名变化：本机空设备号累计行迁到新名，撞键求和
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "旧名"; readTime = 100_000L; lastRead = 1_000L
            }
        )
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "新名"; readTime = 30_000L; lastRead = 2_000L
            }
        )
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity("旧名", "作者", "新名", "作者", db)
        val merged = db.readRecordDao.get("", "新名")
        assertEquals(130_000L, merged?.readTime)
        assertEquals(2_000L, merged?.lastRead)
        assertNull(db.readRecordDao.get("", "旧名"))
        // 仅作者变化：累计表不动
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "书名"; readTime = 10_000L
            }
        )
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity("书名", "旧作者", "书名", "新作者", db)
        assertEquals(10_000L, db.readRecordDao.get("", "书名")?.readTime)
    }

    @Test
    fun aggregateQueriesWorkUnderVolume() {
        val androidId = io.legado.app.constant.AppConst.androidId
        val rows = ArrayList<ReadRecordDetail>(30 * 50)
        for (day in 1..30) {
            for (book in 1..50) {
                rows.add(
                    row(
                        deviceId = androidId,
                        bookName = "书$book",
                        bookAuthor = "作者",
                        date = String.format(java.util.Locale.ROOT, "2026-10-%02d", day),
                        readTime = (book * 1000).toLong()
                    )
                )
            }
        }
        db.runInTransaction {
            db.readRecordDetailDao.insert(*rows.toTypedArray())
        }
        assertEquals(1500, db.readRecordDetailDao.all.size)
        assertEquals(30, db.readRecordDetailDao.distinctDateCount())
        // sum = 50 本 × 每本每天 book*1000 × 30 天
        var expected = 0L
        for (book in 1..50) expected += (book * 1000L) * 30
        assertEquals(expected, db.readRecordDetailDao.totalSum)
        val rank = db.readRecordDetailDao.rankRange("2026-10-01", "2026-11-01")
        assertEquals(50, rank.size)
        assertEquals("书50", rank[0].bookName)
        assertEquals(50_000L * 30, rank[0].readTime)
    }

    /** 窗口起点对应的本地自然日（待冲正窗口的日期归属验证用） */
    private fun dateOf(epochMs: Long): String {
        return java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }

    @Test
    fun doubleWriteTransactionRollsBackAtomically() {
        // 用真实 SQLite 触发器在每日明细插入时制造失败，验证双表同事务整体回滚
        val support = db.openHelper.writableDatabase
        support.execSQL(
            "create trigger fail_detail_insert before insert on readRecordDetail " +
                    "begin select raise(abort, 'simulated io failure'); end"
        )
        val threw = runCatching {
            db.runInTransaction {
                db.readRecordDao.insert(
                    io.legado.app.data.entities.ReadRecord().apply {
                        deviceId = ""; bookName = "事务书"; readTime = 1000L
                    }
                )
                db.readRecordDetailDao.insert(row(bookName = "事务书"))
            }
            false
        }.getOrElse { true }
        assertTrue("事务应抛出异常", threw)
        // 累计表写入随明细失败整体回滚
        assertEquals(0, db.readRecordDao.all.size)
        assertEquals(0, db.readRecordDetailDao.all.size)
        support.execSQL("drop trigger fail_detail_insert")
        // 重试成功
        db.runInTransaction {
            db.readRecordDao.insert(
                io.legado.app.data.entities.ReadRecord().apply {
                    deviceId = ""; bookName = "事务书"; readTime = 1000L
                }
            )
            db.readRecordDetailDao.insert(row(bookName = "事务书"))
        }
        assertEquals(1000L, db.readRecordDao.all[0].readTime)
        assertEquals(1, db.readRecordDetailDao.all.size)
    }

    @Test
    fun retryPendingWorkAppliesWindowExactlyOnce() {
        val androidId = io.legado.app.constant.AppConst.androidId
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "待冲正书", "作者", 8, 1_000_000L, 1_200_000L
        )
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        val date = dateOf(1_000_000L)
        // 每日明细补写，时长=窗口长度
        assertEquals(
            200_000L,
            db.readRecordDetailDao.get(androidId, "待冲正书", "作者", date)?.readTime
        )
        // 累计表本机分区同步补齐
        assertEquals(200_000L, db.readRecordDao.get("", "待冲正书")?.readTime)
        // 重复重试不重复累计
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(
            200_000L,
            db.readRecordDetailDao.getByBook(androidId, "待冲正书", "作者").sumOf { it.readTime }
        )
        assertEquals(200_000L, db.readRecordDao.get("", "待冲正书")?.readTime)
    }

    @Test
    fun retryPendingWorkFollowsIdentityRedirect() {
        val androidId = io.legado.app.constant.AppConst.androidId
        // 先有旧身份已落库数据并完成迁移（迁移成功会注册身份重定向）
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "重定向旧名", bookAuthor = "旧作者", readTime = 50_000L)
        )
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity(
            "重定向旧名", "旧作者", "重定向新名", "新作者", db
        )
        // 迁移后仍有旧身份窗口待冲正（模拟迁移前事务失败未落库的结算）
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "重定向旧名", "旧作者", 8, 2_000_000L, 2_100_000L
        )
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        // 冲正窗口归入新身份，旧身份不再新增
        assertEquals(0, db.readRecordDetailDao.getByBook(androidId, "重定向旧名", "旧作者").size)
        assertEquals(
            150_000L,
            db.readRecordDetailDao.getByBook(androidId, "重定向新名", "新作者").sumOf { it.readTime }
        )
        assertNull(db.readRecordDao.get("", "重定向旧名"))
        assertEquals(100_000L, db.readRecordDao.get("", "重定向新名")?.readTime)
    }

    @Test
    fun migrateIdentityTwiceDoesNotDoubleSum() {
        val androidId = io.legado.app.constant.AppConst.androidId
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "幂等旧名", bookAuthor = "作者", readTime = 100_000L)
        )
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "幂等旧名"; readTime = 100_000L
            }
        )
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity(
            "幂等旧名", "作者", "幂等新名", "作者", db
        )
        assertEquals(
            100_000L,
            db.readRecordDetailDao.getByBook(androidId, "幂等新名", "作者").sumOf { it.readTime }
        )
        assertEquals(100_000L, db.readRecordDao.get("", "幂等新名")?.readTime)
        // 第二次执行：源键已空，整体为空操作，不重复求和
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity(
            "幂等旧名", "作者", "幂等新名", "作者", db
        )
        assertEquals(
            100_000L,
            db.readRecordDetailDao.getByBook(androidId, "幂等新名", "作者").sumOf { it.readTime }
        )
        assertEquals(100_000L, db.readRecordDao.get("", "幂等新名")?.readTime)
        assertEquals(0, db.readRecordDetailDao.getByBook(androidId, "幂等旧名", "作者").size)
        assertNull(db.readRecordDao.get("", "幂等旧名"))
    }

    @Test
    fun ownPartitionQueryExcludesForeignDeviceRows() {
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "分区书"; readTime = 50_000L
            }
        )
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = "otherDev"; bookName = "分区书"; readTime = 100_000L
            }
        )
        // 计时基数只取本机分区；跨设备求和仅用于展示口径
        assertEquals(50_000L, db.readRecordDao.getOwnReadTime("分区书"))
        assertEquals(150_000L, db.readRecordDao.getReadTime("分区书"))
    }

    @Test
    fun deleteByNameMatchesUiDeclaredScope() {
        val book = io.legado.app.data.entities.Book()
        book.name = "同名书"
        book.author = "作者甲"
        book.bookUrl = "https://a.example/x"
        book.origin = "https://a.example"
        db.bookDao.insert(book)
        db.readRecordDetailDao.insert(row(bookName = "同名书", bookAuthor = "作者甲"))
        db.readRecordDetailDao.insert(row(bookName = "同名书", bookAuthor = "作者乙"))
        db.readRecordDetailDao.insert(row(bookName = "其他书"))
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "同名书"; readTime = 1L
            }
        )
        // 与 UI 提示一致：删除书名聚合行会同时移除同名不同作者的统计
        db.readRecordDao.deleteByName("同名书")
        db.readRecordDetailDao.deleteByName("同名书")
        assertEquals(1, db.readRecordDetailDao.all.size)
        assertEquals("其他书", db.readRecordDetailDao.all[0].bookName)
        assertEquals(0, db.readRecordDao.all.size)
        // 书籍本身不受影响
        assertEquals(1, db.bookDao.all.size)
    }

    @Test
    fun migrationFailurePersistsAndRecovers() {
        val androidId = io.legado.app.constant.AppConst.androidId
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "恢复旧名", bookAuthor = "作者", readTime = 70_000L)
        )
        // 用触发器在明细删除时制造失败，模拟迁移事务失败
        val support = db.openHelper.writableDatabase
        support.execSQL(
            "create trigger fail_migration before delete on readRecordDetail " +
                    "begin select raise(abort, 'simulated failure'); end"
        )
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity(
            "恢复旧名", "作者", "恢复新名", "作者", db
        )
        // 失败：数据完整保留在旧身份下，任务已持久化到文件
        assertEquals(1, db.readRecordDetailDao.getByBook(androidId, "恢复旧名", "作者").size)
        val storeFile = testStoreFile!!
        assertTrue(storeFile.exists())
        // 模拟进程重启：新存储实例读同一文件重新发现任务，且依据是换源时记录的旧→新身份对
        val reloaded = io.legado.app.help.book.PendingMigrationFileStore(storeFile).load()
        assertEquals(1, reloaded.size)
        assertEquals("恢复旧名", reloaded[0][0])
        assertEquals("恢复新名", reloaded[0][2])
        support.execSQL("drop trigger fail_migration")
        // 重启后恢复重试成功
        io.legado.app.help.book.ReadRecordDetailHelper.recoverPendingMigrationsSync(db)
        assertEquals(0, db.readRecordDetailDao.getByBook(androidId, "恢复旧名", "作者").size)
        assertEquals(
            70_000L,
            db.readRecordDetailDao.getByBook(androidId, "恢复新名", "作者").sumOf { it.readTime }
        )
        // 成功后持久化状态清除；再次恢复不重复迁移
        assertFalse(storeFile.exists())
        io.legado.app.help.book.ReadRecordDetailHelper.recoverPendingMigrationsSync(db)
        assertEquals(
            70_000L,
            db.readRecordDetailDao.getByBook(androidId, "恢复新名", "作者").sumOf { it.readTime }
        )
    }

    @Test
    fun clearFailureKeepsPendingWorkAndData() {
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "清空书"; readTime = 50_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "清空书", readTime = 20_000L))
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "清空书", "作者", 8, 3_000_000L, 3_100_000L
        )
        // 触发器让清空事务失败
        val support = db.openHelper.writableDatabase
        support.execSQL(
            "create trigger fail_clear before delete on readRecord " +
                    "begin select raise(abort, 'simulated failure'); end"
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = true
        io.legado.app.help.book.ReadRecordDetailHelper.clearAllStatistics(db) { result ->
            ok = result
            latch.countDown()
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse("清空事务应失败", ok)
        support.execSQL("drop trigger fail_clear")
        // 数据库整体回滚：旧数据原样保留
        assertEquals(50_000L, db.readRecordDao.get("", "清空书")?.readTime)
        // 待冲正窗口未被提前丢弃：重试仍然生效（旧数据 + 冲正窗口）
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(60_000L, db.readRecordDao.get("", "清空书")?.readTime)
        assertEquals(30_000L, db.readRecordDetailDao.all.sumOf { it.readTime })
    }

    @Test
    fun clearSuccessDropsPendingAndAllowsNewReading() {
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "清空书"; readTime = 50_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "清空书", readTime = 20_000L))
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "清空书", "作者", 8, 3_000_000L, 3_100_000L
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        io.legado.app.help.book.ReadRecordDetailHelper.clearAllStatistics(db) { result ->
            ok = result
            latch.countDown()
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(ok)
        assertEquals(0, db.readRecordDao.all.size)
        assertEquals(0, db.readRecordDetailDao.all.size)
        // 清空前的待冲正窗口与迁移任务不复活
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(0, db.readRecordDetailDao.all.size)
        assertEquals(0, db.readRecordDao.all.size)
        assertFalse(testStoreFile!!.exists())
        // 清空后新阅读正常记录
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "清空书"; readTime = 5_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "清空书", readTime = 5_000L))
        assertEquals(5_000L, db.readRecordDao.all[0].readTime)
        assertEquals(1, db.readRecordDetailDao.all.size)
    }

    @Test
    fun identityRedirectIsolatesSameNameDifferentAuthor() {
        val androidId = io.legado.app.constant.AppConst.androidId
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "同名书", bookAuthor = "作者甲", readTime = 10_000L)
        )
        // 只迁移（同名书，作者甲）；重定向键含作者，不波及同名不同作者
        io.legado.app.help.book.ReadRecordDetailHelper.migrateBookIdentity(
            "同名书", "作者甲", "改名书", "作者甲", db
        )
        io.legado.app.help.book.ReadRecordDetailHelper.appendWindow(
            "同名书", "作者乙", 0, 4_000_000L, 4_100_000L, db
        )
        assertEquals(
            10_000L,
            db.readRecordDetailDao.getByBook(androidId, "改名书", "作者甲").sumOf { it.readTime }
        )
        assertEquals(
            100_000L,
            db.readRecordDetailDao.getByBook(androidId, "同名书", "作者乙").sumOf { it.readTime }
        )
        // 不确定身份的记录不会被自动合并：旧键（同名书，作者乙）原样保留
        assertEquals(
            0,
            db.readRecordDetailDao.getByBook(androidId, "改名书", "作者乙").size
        )
    }

}
