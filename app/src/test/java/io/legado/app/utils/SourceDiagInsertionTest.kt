package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [诊断] 插入点回归测试：证明「只加日志，没有改业务」。
 *
 * 覆盖审批要求里必须固化的几条：
 * - `if (it.bookUrl != callBack.oldBookUrl)` 及其调用行为原样保留；
 * - 七步迁移的调用顺序与步骤编号未变，且「开始 / 失败 / 提交成功」三态可区分；
 * - 换域名前的新 URL 占用检查未变；
 * - 原有四条 logcat 日志没有被替换；
 * - 导入对比的四组状态判定未变，且不再逐条记录、不再全表扫描；
 * - 各读取路径的「没有源」判定与写库调用未变；
 * - 已移出本轮的缓存 / 书架 / 阅读器入口回到原样。
 */
class SourceDiagInsertionTest {

    private val adapterPath =
        "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceAdapter.kt"
    private val dialogPath =
        "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceDialog.kt"
    private val editVmPath =
        "app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditViewModel.kt"
    private val importVmPath =
        "app/src/main/java/io/legado/app/ui/association/ImportBookSourceViewModel.kt"
    private val bookInfoVmPath =
        "app/src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
    private val bookInfoActivityPath =
        "app/src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
    private val readBookPath = "app/src/main/java/io/legado/app/model/ReadBook.kt"
    private val readBookVmPath =
        "app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt"
    private val mainVmPath = "app/src/main/java/io/legado/app/ui/main/MainViewModel.kt"
    private val cacheBookPath = "app/src/main/java/io/legado/app/model/CacheBook.kt"

    @Test
    fun `换源点击判定条件与调用保持原样`() {
        val src = DiagSourceText.source(adapterPath)
        val block = DiagSourceText.block(
            src,
            "holder.itemView.setOnClickListener {",
            "holder.itemView.onLongClick {"
        )
        assertEquals(
            1,
            DiagSourceText.countOf(block, "if (it.bookUrl != callBack.oldBookUrl) {")
        )
        DiagSourceText.assertOrder(
            block,
            "SourceDiag.click(",
            "if (it.bookUrl != callBack.oldBookUrl) {",
            "callBack.changeTo(it)"
        )
    }

    @Test
    fun `换源回调接口原有成员全部保留`() {
        val src = DiagSourceText.source(adapterPath)
        val block = DiagSourceText.block(src, "interface CallBack {", "}")
        listOf(
            "val oldBookUrl: String?",
            "val oldBookOrigin: String?",
            "fun changeTo(searchBook: SearchBook)",
            "fun topSource(searchBook: SearchBook)",
            "fun bottomSource(searchBook: SearchBook)",
            "fun editSource(searchBook: SearchBook)",
            "fun disableSource(searchBook: SearchBook)",
            "fun deleteSource(searchBook: SearchBook)",
            "fun setBookScore(searchBook: SearchBook, score: Int)",
            "fun getBookScore(searchBook: SearchBook): Int"
        ).forEach { member ->
            assertTrue("回调接口成员缺失: $member", block.contains(member))
        }
    }

    @Test
    fun `七步迁移顺序与步骤编号未被改变`() {
        val src = DiagSourceText.source(editVmPath)
        val block = DiagSourceText.block(
            src,
            "appDb.runInTransaction {",
            "} catch (e: Exception) {"
        )
        DiagSourceText.assertOrder(
            block,
            "insertOrAbort(source); lastStep = 1",
            "replaceChapterUrls(oldUrl, source.bookSourceUrl); lastStep = 2",
            "replaceBookUrls(oldUrl, source.bookSourceUrl); lastStep = 3",
            "updateBookSourceUrl(oldUrl, source.bookSourceUrl); lastStep = 4",
            "searchBookDao.updateOrigin(oldUrl, source.bookSourceUrl); lastStep = 5",
            "bookSourceDao.delete(oldUrl); lastStep = 6",
            "cacheDao.deleteSourceVariables(oldUrl); lastStep = 7"
        )
        // 迁移开始前仍先关闭外键即时检查
        DiagSourceText.assertOrder(
            block,
            "execSQL(\"PRAGMA defer_foreign_keys = ON\")",
            "insertOrAbort(source); lastStep = 1"
        )
    }

    @Test
    fun `迁移开始失败提交三态位置正确`() {
        val src = DiagSourceText.source(editVmPath)
        DiagSourceText.assertOrder(
            src,
            "SourceDiag.sourceSave(",
            "SourceDiag.migrateBegin(",
            "appDb.runInTransaction {",
            "SourceDiag.migrateStep(2,",
            "SourceDiag.migrateStep(3,",
            "} catch (e: Exception) {",
            "SourceDiag.migrateFail(",
            "step\${lastStep + 1} failed",
            "SourceDiag.migrateCommit(",
            "SourceConfig.removeSource(oldUrl)"
        )
        // 提交成功事件必须落在 try/catch 之后，不能出现在事务内部
        val inTransaction = DiagSourceText.block(
            src,
            "appDb.runInTransaction {",
            "} catch (e: Exception) {"
        )
        assertFalse(inTransaction, inTransaction.contains("migrateCommit"))
        assertFalse(inTransaction, inTransaction.contains("SOURCE_MIGRATE_COMMIT"))
        // 逐步日志精简：第 7 步与缓存统计已移除
        assertFalse(src, src.contains("migrateStep(7)"))
        assertFalse(src, src.contains("migrateCache"))
        assertFalse(src, src.contains("cacheMoved"))
    }

    @Test
    fun `换域名前的新地址占用检查保持原样`() {
        val src = DiagSourceText.source(editVmPath)
        val block = DiagSourceText.block(
            src,
            "// 换域名前检查新 URL 是否已被其他书源占用",
            "val tag = \"BookSourceMigration\""
        )
        DiagSourceText.assertOrder(
            block,
            "if (urlChanged && appDb.bookSourceDao.has(source.bookSourceUrl)) {",
            "SourceDiag.sourceSaveBlocked(",
            "throw NoStackTraceException("
        )
    }

    @Test
    fun `原有四条 logcat 日志没有被替换`() {
        val src = DiagSourceText.source(editVmPath)
        assertEquals(4, DiagSourceText.countOf(src, "android.util.Log.i("))
    }

    @Test
    fun `缓存目录迁移循环结构未变`() {
        val src = DiagSourceText.source(editVmPath)
        val block = DiagSourceText.block(
            src,
            "// 迁移书籍缓存目录",
            "}.onSuccess {"
        )
        DiagSourceText.assertOrder(
            block,
            "oldBooks.forEach { oldBook ->",
            "source.bookSourceUrl + oldBook.bookUrl.removePrefix(oldUrl)",
            "appDb.bookDao.getBook(newBookUrl)?.let { newBook ->",
            "BookHelp.updateCacheFolder(oldBook, newBook)"
        )
    }

    @Test
    fun `导入对比的四组状态判定未变且不再逐条记录`() {
        val src = DiagSourceText.source(importVmPath)
        val block = DiagSourceText.block(
            src,
            "private fun comparisonSource() {",
            "successLiveData.postValue(allSources.size)"
        )
        DiagSourceText.assertOrder(
            block,
            "allSources.forEach {",
            "checkSources.add(source)",
            "selectStatus.add(source == null || source.lastUpdateTime < it.lastUpdateTime)",
            "newSourceStatus.add(source == null)",
            "updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)"
        )
        // 原有的 forEach 结构未被改成 forEachIndexed，也没有逐条导入日志
        assertFalse(block, block.contains("forEachIndexed"))
        assertFalse(block, block.contains("IMPORT_ITEM"))
        assertFalse(block, block.contains("SourceDiag.importItem"))
    }

    @Test
    fun `导入不新增数据库查询也不做全表扫描`() {
        val src = DiagSourceText.source(importVmPath)
        assertEquals(1, DiagSourceText.countOf(src, "appDb."))
        assertFalse(src, src.contains("allPart"))
        assertFalse(src, src.contains("IMPORT_HOST_OVERLAP"))
    }

    @Test
    fun `导入落库与错误上报调用保持原样`() {
        val src = DiagSourceText.source(importVmPath)
        DiagSourceText.assertOrder(
            src,
            "SourceHelp.insertBookSource(*selectSource.toTypedArray())",
            "ContentProcessor.upReplaceRules()",
            "SourceDiag.importSummary("
        )
        DiagSourceText.assertOrder(
            src,
            "SourceDiag.importFail(",
            "errorLiveData.postValue(",
            "AppLog.put("
        )
    }

    @Test
    fun `详情页与目录页的无源判定保持原样`() {
        val src = DiagSourceText.source(bookInfoVmPath)
        assertEquals(2, DiagSourceText.countOf(src, "val bookSource = bookSource ?: let {"))
        assertEquals(
            2,
            DiagSourceText.countOf(src, "context.toastOnUi(R.string.error_no_source)")
        )
    }

    @Test
    fun `详情页书源入口的校验顺序保持原样`() {
        val src = DiagSourceText.source(bookInfoActivityPath)
        val block = DiagSourceText.block(
            src,
            "tvOrigin.setOnClickListener {",
            "tvChangeSource.setOnClickListener {"
        )
        DiagSourceText.assertOrder(
            block,
            "if (!appDb.bookSourceDao.has(book.origin)) {",
            "SourceDiag.lookup(\"originClick\"",
            "toastOnUi(R.string.error_no_source)"
        )
        // 命中「没有源」时仍然直接 return@let，不继续走编辑书源
        assertTrue(
            DiagSourceText
                .block(block, "if (!appDb.bookSourceDao.has(book.origin)) {", "}")
                .contains("return@let")
        )
    }

    @Test
    fun `阅读页无源提示与自动换源顺序保持原样`() {
        val src = DiagSourceText.source(readBookVmPath)
        val block = DiagSourceText.block(
            src,
            "if (!book.isLocal && ReadBook.bookSource == null) {",
            "autoChangeSource(book.name, book.author)"
        )
        DiagSourceText.assertOrder(
            block,
            "if (ReadBook.chapterSize == 0) {",
            "ReadBook.upMsg(context.getString(R.string.error_no_source))",
            "SourceDiag.lookup("
        )
    }

    @Test
    fun `已移出本轮的三个入口不再包含诊断埋点`() {
        listOf(cacheBookPath, readBookPath, mainVmPath).forEach { path ->
            val src = DiagSourceText.source(path)
            assertFalse("$path 不应再出现 SourceDiag", src.contains("SourceDiag"))
        }
        // 原有业务分支保持原样
        val cacheBook = DiagSourceText.source(cacheBookPath)
        assertTrue(
            cacheBook.contains(
                "val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return null"
            )
        )
        val readBook = DiagSourceText.source(readBookPath)
        assertTrue(
            DiagSourceText
                .block(readBook, "} ?: let {", "}")
                .contains("bookSource = null")
        )
        val mainVm = DiagSourceText.source(mainVmPath)
        DiagSourceText.assertOrder(
            mainVm,
            "if (source == null) {",
            "if (!book.isUpError) {",
            "book.addType(BookType.updateError)",
            "appDb.bookDao.update(book)"
        )
    }

    @Test
    fun `换源列表定位仍按原顺序执行`() {
        val src = DiagSourceText.source(dialogPath)
        val block = DiagSourceText.block(
            src,
            "private fun scrollToDurSource() {",
            "override fun changeTo(searchBook: SearchBook) {"
        )
        DiagSourceText.assertOrder(
            block,
            "adapter.getItems().forEachIndexed { index, searchBook ->",
            "if (searchBook.bookUrl == oldBookUrl) {",
            "scrollToPositionWithOffset(index, 60.dpToPx())"
        )
        assertFalse(block, block.contains("changeSourceList"))
    }

    @Test
    fun `换源获取目录的调用链保持原样`() {
        val src = DiagSourceText.source(dialogPath)
        val block = DiagSourceText.block(
            src,
            "private fun changeSource(searchBook: SearchBook, onSuccess: (() -> Unit)? = null) {",
            "waitDialog.setOnCancelListener {"
        )
        DiagSourceText.assertOrder(
            block,
            "waitDialog.setText(R.string.load_toc)",
            "waitDialog.show()",
            "val book = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()",
            "SourceDiag.changeSource(",
            "val coroutine = viewModel.getToc(book, { toc, source ->",
            "waitDialog.dismiss()",
            "callBack?.changeTo(source, book, toc)",
            "onSuccess?.invoke()"
        )
        assertTrue(block.contains("AppLog.put(\"换源获取目录出错"))
    }
}
