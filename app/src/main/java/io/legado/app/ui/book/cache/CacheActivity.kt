package io.legado.app.ui.book.cache

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityCacheBookBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogSelectSectionExportBinding
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.cnCompare
import io.legado.app.utils.enableCustomExport
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setIconCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.verificationField
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.math.max

/**
 * cache/download 缓存界面
 */
class CacheActivity : VMBaseActivity<ActivityCacheBookBinding, CacheViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    CacheAdapter.CallBack {

    override val binding by viewBinding(ActivityCacheBookBinding::inflate)
    override val viewModel by viewModels<CacheViewModel>()

    private val exportBookPathKey = "exportBookPath"
    private val exportTypes = arrayListOf("txt", "epub")
    private val layoutManager by lazy { LinearLayoutManager(this) }
    private val adapter by lazy { CacheAdapter(this, this) }
    private var booksFlowJob: Job? = null
    private var menu: Menu? = null
    private val groupList: ArrayList<BookGroup> = arrayListOf()
    private var groupId: Long = -1
    private var scrollToTopAfterNextListUpdate = false
    private val bookListState by lazy {
        CacheBookListState { book ->
            // 与列表展示一致:已缓存章节数来自扫描到的缓存章节,用于判断是否下载完成
            viewModel.cacheChapters[book.bookUrl]?.size ?: 0
        }
    }

    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        var isReadyPath = false
        var dirPath = ""
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                ACache.get().put(exportBookPathKey, uri.toString())
                dirPath = uri.toString()
                isReadyPath = true
            } else {
                uri.path?.let { path ->
                    ACache.get().put(exportBookPathKey, path)
                    dirPath = path
                    isReadyPath = true
                }
            }
        }
        if (!isReadyPath) {
            return@registerForActivityResult
        }
        if (enableCustomExport()) {// 启用自定义导出 and 导出类型为Epub
            configExportSection(dirPath, result.requestCode)
        } else {
            startExport(dirPath, result.requestCode)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            binding.titleBar.subtitle = withContext(IO) {
                appDb.bookGroupDao.getByID(groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
        }
        initRecyclerView()
        initGroupData()
        initBookData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_cache, menu)
        this.menu = menu
        upSortMenuChecked(menu)
        menu.iconItemOnLongClick(R.id.menu_download) {
            PopupMenu(this, it).apply {
                inflate(R.menu.book_cache_download)
                this.menu.applyOpenTint(this@CacheActivity)
                setOnMenuItemClickListener(this@CacheActivity)
            }.show()
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upSortMenuChecked(menu)
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        upSortMenuChecked(menu)
        menu.findItem(R.id.menu_enable_replace)?.isChecked = AppConfig.exportUseReplace
        // 菜单打开时读取状态[enableCustomExport]
        menu.findItem(R.id.menu_enable_custom_export)?.isChecked = AppConfig.enableCustomExport
        menu.findItem(R.id.menu_export_no_chapter_name)?.isChecked = AppConfig.exportNoChapterName
        menu.findItem(R.id.menu_export_web_dav)?.isChecked = AppConfig.exportToWebDav
        menu.findItem(R.id.menu_export_pics_file)?.isChecked = AppConfig.exportPictureFile
        menu.findItem(R.id.menu_parallel_export)?.isChecked = AppConfig.parallelExportBook
        menu.findItem(R.id.menu_export_type)?.title =
            "${getString(R.string.export_type)}(${getTypeName()})"
        menu.findItem(R.id.menu_export_charset)?.title =
            "${getString(R.string.export_charset)}(${AppConfig.exportCharset})"
        return super.onMenuOpened(featureId, menu)
    }

    private fun upMenu() {
        menu?.findItem(R.id.menu_book_group)?.subMenu?.let { subMenu ->
            subMenu.removeGroup(R.id.menu_group)
            groupList.forEach { bookGroup ->
                subMenu.add(R.id.menu_group, bookGroup.order, Menu.NONE, bookGroup.groupName)
            }
        }
    }

    /**
     * 菜单勾选状态必须与真实排序模式保持一致。
     * 注意:单选组内对未选中项执行 isChecked = false 会触发
     * MenuBuilder.setExclusiveItemChecked,反而把它选中;
     * 因此只把目标项设为 true,由单选组自动取消另一项。
     */
    private fun upSortMenuChecked(menu: Menu? = this.menu) {
        val checkedItemId =
            if (bookListState.sortMode == CacheBookListState.SortMode.INCOMPLETE_FIRST) {
                R.id.menu_sort_incomplete_first
            } else {
                R.id.menu_sort_default
            }
        menu?.findItem(checkedItemId)?.isChecked = true
    }

    /**
     * 用户主动切换排序:同步菜单勾选,并在列表 diff 应用后回到顶部。
     * 排序模式没有变化时不重复提交列表,也不回顶。
     */
    private fun selectSortMode(mode: CacheBookListState.SortMode) {
        val changed = bookListState.sortMode != mode
        bookListState.setSortMode(mode)
        upSortMenuChecked()
        if (!changed) return
        scrollToTopAfterNextListUpdate = true
        adapter.setItems(bookListState.items)
    }

    /**
     * 菜单按下回调
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_download,
            R.id.menu_download_after -> {
                if (!CacheBook.isRun) sureCacheBook {
                    adapter.getItems().forEach { book ->
                        CacheBook.start(
                            this@CacheActivity,
                            book,
                            book.durChapterIndex,
                            book.lastChapterIndex
                        )
                    }
                } else {
                    CacheBook.stop(this@CacheActivity)
                }
            }

            R.id.menu_download_all -> {
                if (!CacheBook.isRun) sureCacheBook {
                    adapter.getItems().forEach { book ->
                        CacheBook.start(
                            this@CacheActivity,
                            book,
                            0,
                            book.lastChapterIndex
                        )
                    }
                } else {
                    CacheBook.stop(this@CacheActivity)
                }
            }

            R.id.menu_sort_default ->
                selectSortMode(CacheBookListState.SortMode.DEFAULT)

            R.id.menu_sort_incomplete_first ->
                selectSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

            R.id.menu_export_all -> exportAll()
            R.id.menu_enable_replace -> AppConfig.exportUseReplace = !item.isChecked
            // 更改菜单状态[enableCustomExport]
            R.id.menu_enable_custom_export -> AppConfig.enableCustomExport = !item.isChecked
            R.id.menu_export_no_chapter_name -> AppConfig.exportNoChapterName = !item.isChecked
            R.id.menu_export_web_dav -> AppConfig.exportToWebDav = !item.isChecked
            R.id.menu_export_pics_file -> AppConfig.exportPictureFile = !item.isChecked
            R.id.menu_parallel_export -> AppConfig.parallelExportBook = !item.isChecked
            R.id.menu_export_folder -> {
                selectExportFolder(-1)
            }

            R.id.menu_export_file_name -> alertExportFileName()
            R.id.menu_export_type -> showExportTypeConfig()
            R.id.menu_export_charset -> showCharsetConfig()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            else -> if (item.groupId == R.id.menu_group) {
                binding.titleBar.subtitle = item.title
                groupId = appDb.bookGroupDao.getByName(item.title.toString())?.groupId ?: 0
                initBookData()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return onCompatOptionsItemSelected(item)
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        // 复用书架同款快速滚动条:滚动时显示,空闲时淡出,短列表不会常驻
        binding.recyclerView.setFastScrollEnabled(true)
        binding.recyclerView.applyNavigationBarPadding()
        adapter.onListChanged = {
            // 只在用户主动切换排序并且列表 diff 已应用后回顶
            if (scrollToTopAfterNextListUpdate) {
                scrollToTopAfterNextListUpdate = false
                binding.recyclerView.scrollToPosition(0)
            }
        }
    }

    private fun initBookData() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { books ->
                val booksDownload = books.filter {
                    !it.isAudio
                }
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> booksDownload.sortedByDescending { it.latestChapterTime }
                    2 -> booksDownload.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> booksDownload.sortedBy { it.order }
                    4 -> booksDownload.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> booksDownload.sortedByDescending { it.durChapterTime }
                }
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle, table = AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("缓存管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { books ->
                // 始终保存页面默认顺序(分组排序结果),排序只作用于展示副本
                bookListState.updateSource(books)
                upBookList()
                viewModel.loadCacheFiles(books)
            }
        }
    }

    private fun upBookList() {
        adapter.setItems(bookListState.items)
    }

    /**
     * 缓存章节数量变化后按最新完成状态校正排序。
     * 仅重新提交展示列表,不修改下载任务、缓存文件或书籍数据。
     */
    private fun resortIfNeeded() {
        if (bookListState.sortMode != CacheBookListState.SortMode.INCOMPLETE_FIRST) return
        if (bookListState.items.map { it.bookUrl } == adapter.getItems().map { it.bookUrl }) return
        upBookList()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("缓存管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
                adapter.notifyDataSetChanged()
                upMenu()
            }
        }
    }

    private fun notifyItemChanged(bookUrl: String) {
        kotlin.runCatching {
            adapter.getItems().forEachIndexed { index, book ->
                if (bookUrl == book.bookUrl) {
                    adapter.notifyItemChanged(index, true)
                    return
                }
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            notifyItemChanged(it)
            resortIfNeeded()
        }
        observeEvent<String>(EventBus.EXPORT_BOOK) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD_STATE) {
            if (!CacheBook.isRun) {
                menu?.findItem(R.id.menu_download)?.let { item ->
                    item.setIconCompat(R.drawable.ic_play_24dp)
                    item.setTitle(R.string.download_start)
                }
                menu?.applyTint(this)
            } else {
                menu?.findItem(R.id.menu_download)?.let { item ->
                    item.setIconCompat(R.drawable.ic_stop_black_24dp)
                    item.setTitle(R.string.stop)
                }
                menu?.applyTint(this)
            }
        }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)
            notifyItemChanged(book.bookUrl)
            resortIfNeeded()
        }
    }

    override fun export(position: Int) {
        val path = ACache.get().getAsString(exportBookPathKey)
        lifecycleScope.launch {
            if (path.isNullOrEmpty() ||
                withContext(IO) { !FileDoc.fromDir(path).checkWrite() }
            ) {
                selectExportFolder(position)
            } else if (enableCustomExport()) {// 启用自定义导出 and 导出类型为Epub
                configExportSection(path, position)
            } else {
                startExport(path, position)
            }
        }
    }

    private fun exportAll() {
        val path = ACache.get().getAsString(exportBookPathKey)
        if (path.isNullOrEmpty()) {
            selectExportFolder(-10)
        } else {
            startExport(path, -10)
        }
    }

    /**
     * 配置自定义导出对话框
     *
     * @param path  导出路径
     * @param position  book位置
     * @author Discut
     * @since 1.0.0
     */
    private fun configExportSection(path: String, position: Int) {

        val alertBinding = DialogSelectSectionExportBinding.inflate(layoutInflater)
            .apply {
                fun verifyExportFileNameJsStr(js: String): Boolean {
                    return tryParesExportFileName(js) && etEpubFilename.text.toString()
                        .isNotEmpty()
                }

                fun enableLyEtEpubFilenameIcon() {
                    lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_CUSTOM
                    lyEtEpubFilename.setEndIconOnClickListener {
                        adapter.getItem(position)?.run {
                            lyEtEpubFilename.helperText =
                                if (verifyExportFileNameJsStr(etEpubFilename.text.toString()))
                                    "${resources.getString(R.string.result_analyzed)}: ${
                                        getExportFileName(
                                            "epub",
                                            1,
                                            etEpubFilename.text.toString()
                                        )
                                    }"
                                else "Error"
                        } ?: run {
                            lyEtEpubFilename.helperText = "Error"
                            AppLog.put("未找到书籍，position is $position")
                        }
                    }
                }
                etEpubSize.setText("1")
                // lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_NONE
                etEpubFilename.text?.append(AppConfig.episodeExportFileName)
                // 存储解析文件名的jsStr
                etEpubFilename.let {
                    it.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus)
                            return@setOnFocusChangeListener
                        it.text?.run {
                            if (verifyExportFileNameJsStr(toString())) {
                                AppConfig.episodeExportFileName = toString()
                            }
                        }
                    }
                }
                tvAllExport.setOnClickListener {
                    cbAllExport.callOnClick()
                }
                tvSelectExport.setOnClickListener {
                    cbSelectExport.callOnClick()
                }
                cbSelectExport.onCheckedChangeListener = { _, isChecked ->
                    if (isChecked) {
                        etEpubSize.isEnabled = true
                        etInputScope.isEnabled = true
                        etEpubFilename.isEnabled = true
                        enableLyEtEpubFilenameIcon()
                        cbAllExport.isChecked = false
                    }
                }
                cbAllExport.onCheckedChangeListener = { _, isChecked ->
                    if (isChecked) {
                        etEpubSize.isEnabled = false
                        etInputScope.isEnabled = false
                        etEpubFilename.isEnabled = false
                        lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_NONE
                        cbSelectExport.isChecked = false
                    }
                }

                etInputScope.onFocusChangeListener =
                    View.OnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            etInputScope.hint = "1-5,8,10-18"
                        } else {
                            etInputScope.hint = ""
                        }
                    }

                // 默认选择自定义导出
                cbSelectExport.callOnClick()
            }
        val alertDialog = alert(titleResource = R.string.select_section_export) {
            customView { alertBinding.root }
            positiveButton(R.string.ok)
            cancelButton()
        }
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            alertBinding.apply {
                if (cbAllExport.isChecked) {
                    startExport(path, position)
                    alertDialog.hide()
                    return@apply
                }
                val epubScope = etInputScope.text.toString()
                if (!verificationField(epubScope)) {
                    etInputScope.error = appCtx.getString(R.string.error_scope_input)//"请输入正确的范围"
                    return@apply
                }
                etInputScope.error = null
                val epubSize = etEpubSize.text.toString().toIntOrNull() ?: 1
                adapter.getItem(position)?.let { book ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", "epub")
                        putExtra("exportPath", path)
                        putExtra("epubSize", epubSize)
                        putExtra("epubScope", epubScope)
                    }
                }
                alertDialog.hide()
            }

        }
    }

    private fun selectExportFolder(exportPosition: Int) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(exportBookPathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        exportDir.launch {
            otherActions = default
            requestCode = exportPosition
        }
    }

    private fun startExport(path: String, exportPosition: Int) {
        val exportType = when (AppConfig.exportType) {
            1 -> "epub"
            else -> "txt"
        }
        if (exportPosition == -10) {
            if (adapter.getItems().isNotEmpty()) {
                adapter.getItems().forEach { book ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", exportType)
                        putExtra("exportPath", path)
                    }
                }
            } else {
                toastOnUi(R.string.no_book)
            }
        } else if (exportPosition >= 0) {
            adapter.getItem(exportPosition)?.let { book ->
                startService<ExportBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("exportType", exportType)
                    putExtra("exportPath", path)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun alertExportFileName() {
        alert(R.string.export_file_name) {
            val message = "Variable: name, author."
            setMessage(message)
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "file name js"
                editView.setText(AppConfig.bookExportFileName)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.bookExportFileName = alertBinding.editView.text?.toString()
            }
            cancelButton()
        }
    }

    private fun getTypeName(): String {
        return exportTypes.getOrElse(AppConfig.exportType) {
            exportTypes[0]
        }
    }

    private fun showExportTypeConfig() {
        selector(R.string.export_type, exportTypes) { _, i ->
            AppConfig.exportType = i
        }
    }

    private fun showCharsetConfig() {
        alert(R.string.set_charset) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "charset name"
                editView.setFilterValues(charsets)
                editView.setText(AppConfig.exportCharset)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.exportCharset = alertBinding.editView.text?.toString() ?: "UTF-8"
            }
            cancelButton()
        }
    }

    private fun sureCacheBook(action: () -> Unit) {
        alert(R.string.draw) {
            setMessage(R.string.sure_cache_book)
            noButton()
            yesButton {
                action.invoke()
            }
        }
    }

    override val cacheChapters: HashMap<String, HashSet<String>>
        get() = viewModel.cacheChapters

    override fun openBook(book: Book) {
        // 与书架一致:只传 bookUrl,阅读进度由 ReadBookActivity 原有机制恢复
        startActivityForBook(book)
    }

    override fun exportProgress(bookUrl: String): Int? {
        return ExportBookService.exportProgress[bookUrl]
    }

    override fun exportMsg(bookUrl: String): String? {
        return ExportBookService.exportMsg[bookUrl]
    }

}