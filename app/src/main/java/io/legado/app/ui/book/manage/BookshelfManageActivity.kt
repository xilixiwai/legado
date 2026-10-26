package io.legado.app.ui.book.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityArrangeBookBinding
import io.legado.app.databinding.DialogDomainReplaceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.contains
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 书架管理
 */
class BookshelfManageActivity :
    VMBaseActivity<ActivityArrangeBookBinding, BookshelfManageViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    BookAdapter.CallBack,
    SourcePickerDialog.Callback,
    GroupSelectDialog.CallBack {

    override val binding by viewBinding(ActivityArrangeBookBinding::inflate)
    override val viewModel by viewModels<BookshelfManageViewModel>()
    override val groupList: ArrayList<BookGroup> = arrayListOf()
    private val groupRequestCode = 22
    private val addToGroupRequestCode = 34
    private val adapter by lazy { BookAdapter(this, this) }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private var booksFlowJob: Job? = null
    private var menu: Menu? = null
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var books: List<Book>? = null
    private val waitDialog by lazy { WaitDialog(this) }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            viewModel.groupName = withContext(IO) {
                appDb.bookGroupDao.getByID(viewModel.groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
            upTitle()
        }
        initSearchView()
        initRecyclerView()
        initOtherView()
        initGroupData()
        upBookDataByGroupId()
    }

    override fun observeLiveBus() {
        viewModel.batchChangeSourceState.observe(this) {
            if (it) {
                waitDialog.setText(R.string.change_source_batch)
                waitDialog.show()
            } else {
                waitDialog.dismiss()
            }
        }
        viewModel.batchChangeSourceProcessLiveData.observe(this) {
            waitDialog.setText(it)
        }
        viewModel.domainReplaceBusy.observe(this) {
            if (it) {
                waitDialog.setText(R.string.change_domain_title)
                // 批量替换的数据库事务无法安全中断：执行期间禁用返回键取消，
                // 避免「取消被静默吞掉、既无反馈也看不到结果」的路径
                waitDialog.setCancelable(false)
                waitDialog.show()
            } else {
                waitDialog.setCancelable(true)
                waitDialog.dismiss()
            }
        }
        viewModel.domainReplaceProcessLiveData.observe(this) {
            waitDialog.setText(it)
        }
        viewModel.domainReplaceCheckLiveData.observe(this) {
            if (it == null) return@observe
            viewModel.domainReplaceCheckLiveData.value = null
            when (it) {
                is DomainReplaceCheckResult.Blocked -> alertDomainReplaceBlocked(it.reason)
                is DomainReplaceCheckResult.Ready -> alertDomainReplacePreview(it.preview)
            }
        }
        viewModel.domainReplaceResultLiveData.observe(this) {
            if (it == null) return@observe
            viewModel.domainReplaceResultLiveData.value = null
            alertDomainReplaceResult(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bookshelf_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        menu.findItem(R.id.menu_open_book_info_by_click_title)?.isChecked =
            AppConfig.openBookInfoByClickTitle
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        selectGroup(groupRequestCode, 0)
    }

    private fun upTitle() {
        searchView.queryHint = getString(R.string.screen) + " • " + viewModel.groupName
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upBookData()
                return false
            }

        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        itemTouchCallback.isCanDrag = AppConfig.bookshelfSort == 3
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        // When this page is opened, it is in selection mode
        dragSelectTouchHelper.activeSlideSelect()
        // Note: need judge selection first, so add ItemTouchHelper after it.
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initOtherView() {
        binding.selectActionBar.setMainActionText(R.string.move_to_group)
        binding.selectActionBar.inflateMenu(R.menu.bookshelf_menage_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
        waitDialog.setOnCancelListener {
            viewModel.batchChangeSourceCoroutine?.cancel()
            // 批量替换域名的数据库事务无法安全取消，不在这里取消该任务；
            // 执行期间对话框本身也被设为不可取消（见 domainReplaceBusy 观察者）
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
                adapter.notifyDataSetChanged()
                upMenu()
            }
        }
    }

    private fun upBookDataByGroupId() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            val bookSort = AppConfig.getBookSortByGroupId(viewModel.groupId)
            appDb.bookDao.flowByGroup(viewModel.groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.catch {
                AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO)
                .conflate().collect {
                    books = it
                    upBookData()
                    itemTouchCallback.isCanDrag = bookSort == 3
                }
        }
    }

    private fun upBookData() {
        books?.let { books ->
            val searchKey = searchView.query
            if (searchKey.isNullOrEmpty()) {
                adapter.setItems(books)
            } else {
                books.filter {
                    it.contains(searchKey.toString())
                }.let {
                    adapter.setItems(it)
                }
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_open_book_info_by_click_title -> {
                AppConfig.openBookInfoByClickTitle = !item.isChecked
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }

            R.id.menu_export_all_use_book_source -> viewModel.saveAllUseBookSourceToFile { file ->
                exportDir.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "bookSource.json",
                        file,
                        "application/json"
                    )
                }
            }

            else -> if (item.groupId == R.id.menu_group) {
                viewModel.groupName = item.title.toString()
                upTitle()
                viewModel.groupId =
                    appDb.bookGroupDao.getByName(item.title.toString())?.groupId ?: 0
                upBookDataByGroupId()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_del_selection -> alertDelSelection()
            R.id.menu_update_enable ->
                viewModel.upCanUpdate(adapter.selection, true)

            R.id.menu_update_disable ->
                viewModel.upCanUpdate(adapter.selection, false)

            R.id.menu_add_to_group -> selectGroup(addToGroupRequestCode, 0)
            R.id.menu_change_source -> showDialogFragment<SourcePickerDialog>()

            R.id.menu_change_domain -> showDomainReplaceDialog()

            R.id.menu_clear_cache -> viewModel.clearCache(adapter.selection)
            R.id.menu_check_selected_interval -> adapter.checkSelectedInterval()
        }
        return false
    }

    private fun upMenu() {
        menu?.findItem(R.id.menu_book_group)?.subMenu?.let { subMenu ->
            subMenu.removeGroup(R.id.menu_group)
            groupList.forEach { bookGroup ->
                subMenu.add(R.id.menu_group, bookGroup.order, Menu.NONE, bookGroup.groupName)
            }
        }
    }

    private fun alertDelSelection() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            val checkBox = CheckBox(this@BookshelfManageActivity).apply {
                setText(R.string.delete_book_file)
                isChecked = LocalConfig.deleteBookOriginal
            }
            val view = LinearLayout(this@BookshelfManageActivity).apply {
                setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                addView(checkBox)
            }
            customView { view }
            okButton {
                LocalConfig.deleteBookOriginal = checkBox.isChecked
                viewModel.deleteBook(adapter.selection, checkBox.isChecked)
            }
            noButton()
        }
    }

    override fun selectGroup(requestCode: Int, groupId: Long) {
        showDialogFragment(
            GroupSelectDialog(groupId, requestCode)
        )
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        when (requestCode) {
            groupRequestCode -> adapter.selection.let { books ->
                val array = Array(books.size) {
                    books[it].copy(group = groupId)
                }
                viewModel.updateBook(*array)
            }

            adapter.groupRequestCode -> {
                adapter.actionItem?.let {
                    viewModel.updateBook(it.copy(group = groupId))
                }
            }

            addToGroupRequestCode -> adapter.selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group or groupId)
                }
                viewModel.updateBook(*array)
            }
        }
    }

    override fun upSelectCount() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.getItems().size)
    }

    override fun updateBook(vararg book: Book) {
        viewModel.updateBook(*book)
    }

    override fun deleteBook(book: Book) {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            var checkBox: CheckBox? = null
            if (book.isLocal) {
                checkBox = CheckBox(this@BookshelfManageActivity).apply {
                    setText(R.string.delete_book_file)
                    isChecked = LocalConfig.deleteBookOriginal
                }
                val view = LinearLayout(this@BookshelfManageActivity).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    addView(checkBox)
                }
                customView { view }
            }
            okButton {
                if (checkBox != null) {
                    LocalConfig.deleteBookOriginal = checkBox.isChecked
                }
                viewModel.deleteBook(listOf(book), LocalConfig.deleteBookOriginal)
            }
        }
    }

    override fun openBook(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    override fun sourceOnClick(source: BookSource) {
        viewModel.changeSource(adapter.selection, source)
        viewModel.batchChangeSourceState.value = true
    }

    /**
     * 「批量替换域名」输入框。
     *
     * 不要求选中书籍，也不要求选择书源：这是一次全库维护操作，作用范围由「旧域名」决定。
     * 点「检查」只做只读扫描，拿到命中数量后再由用户确认是否执行。
     */
    private fun showDomainReplaceDialog() {
        val binding = DialogDomainReplaceBinding.inflate(layoutInflater)
        val dialog = alert(title = getString(R.string.change_domain_title)) {
            customView { binding.root }
        }
        upRegexSymbolBar(binding)
        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnCheck.setOnClickListener {
            val oldInput = binding.tieOldDomain.text?.toString().orEmpty()
            val newInput = binding.tieNewDomain.text?.toString().orEmpty()
            dialog.dismiss()
            viewModel.checkDomainReplace(oldInput, newInput)
        }
    }

    /** 旧域名输入框下方的正则快捷符号栏：点击把符号插入旧域名光标位置，不影响新域名输入框 */
    private fun upRegexSymbolBar(binding: DialogDomainReplaceBinding) {
        binding.llRegexSymbols.removeAllViews()
        RegexSymbolBar.symbols.forEach { symbol ->
            val key = AccentTextView(this, null).apply {
                text = symbol
                textSize = 15f
                setPaddingRelative(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
                // 不抢焦点：点击后输入框保持焦点，软键盘与撤销等编辑行为不受影响
                isFocusable = false
                setOnClickListener { insertRegexSymbol(binding.tieOldDomain, symbol) }
            }
            binding.llRegexSymbols.addView(key)
        }
    }

    private fun insertRegexSymbol(editor: EditText, symbol: String) {
        val editable = editor.editableText
        val (newText, cursor) = RegexSymbolBar.insert(
            text = editable.toString(),
            selectionStart = editor.selectionStart,
            selectionEnd = editor.selectionEnd,
            symbol = symbol,
        )
        // 用 Editable 原地替换而不是 setText：不重置输入连接、不清掉焦点与撤销栈
        editable.replace(0, editable.length, newText)
        editor.setSelection(cursor)
    }

    private fun alertDomainReplaceBlocked(reason: String) {
        alert(title = getString(R.string.change_domain_title), message = reason) {
            okButton()
        }
    }

    private fun alertDomainReplacePreview(preview: DomainReplacePreview) {
        val rule = preview.rule
        val message = buildString {
            appendLine(
                getString(R.string.change_domain_preview_header, rule.oldInput, rule.newDomain)
            )
            appendLine(getString(R.string.change_domain_pattern, rule.pattern))
            appendLine(getString(R.string.change_domain_hit_books, preview.hits.bookUrl))
            appendLine(getString(R.string.change_domain_hit_toc, preview.hits.tocUrl))
            appendLine(
                getString(R.string.change_domain_hit_chapter_book, preview.hits.chapterBookUrl)
            )
            appendLine(getString(R.string.change_domain_hit_chapter_url, preview.hits.chapterUrl))
            appendLine(
                getString(R.string.change_domain_hit_chapter_base, preview.hits.chapterBaseUrl)
            )
            appendLine(
                getString(
                    R.string.change_domain_hit_chapter_resource,
                    preview.hits.chapterResourceUrl
                )
            )
            appendLine(getString(R.string.change_domain_hit_total, preview.hits.total))
            if (preview.hostMatches.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.change_domain_matched_hosts))
                preview.hostMatches.take(MAX_RESULT_DETAIL_LINES).forEach {
                    appendLine(
                        getString(
                            R.string.change_domain_host_line,
                            it.oldHost,
                            rule.newDomain,
                            it.count
                        )
                    )
                }
                if (preview.hostMatches.size > MAX_RESULT_DETAIL_LINES) {
                    appendLine("…")
                }
            }
            if (preview.authorityChanges.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.change_domain_authority_changes))
                preview.authorityChanges.take(MAX_RESULT_DETAIL_LINES).forEach {
                    appendLine("· ${it.field}  ${it.oldAuthority} → ${it.newAuthority}（${it.count}）")
                }
                if (preview.authorityChanges.size > MAX_RESULT_DETAIL_LINES) {
                    appendLine("…")
                }
            }
            if (preview.booksToRename.isNotEmpty()) {
                appendLine()
                append(
                    getString(
                        R.string.change_domain_cache_rename,
                        preview.booksToRename.take(MAX_RESULT_DETAIL_LINES).joinToString("、") +
                                if (preview.booksToRename.size > MAX_RESULT_DETAIL_LINES) "…" else ""
                    )
                )
            }
            appendLine()
            appendLine()
            appendLine(getString(R.string.change_domain_key_check))
            if (preview.conflicts.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.change_domain_conflicts))
                preview.conflicts.take(MAX_RESULT_DETAIL_LINES).forEach {
                    appendLine("· $it")
                }
            }
        }
        alert(
            title = getString(R.string.change_domain_preview_title),
            message = message
        ) {
            if (preview.conflicts.isEmpty()) {
                positiveButton(getString(R.string.change_domain_execute)) {
                    viewModel.executeDomainReplace(rule.oldInput, rule.newDomain)
                }
            }
            cancelButton()
        }
    }

    private fun alertDomainReplaceResult(result: DomainReplaceResult) {
        val abortReason = result.abortReason
        if (abortReason != null) {
            val message = getString(R.string.change_domain_result_aborted, abortReason)
            alert(
                title = getString(R.string.change_domain_result_title),
                message = message
            ) {
                neutralButton(getString(R.string.copy_text)) { sendToClip(message) }
                okButton()
            }
            return
        }
        val message = buildString {
            result.rule?.let {
                appendLine(getString(R.string.change_domain_preview_header, it.oldInput, it.newDomain))
            }
            appendLine(getString(R.string.change_domain_result_books, result.booksUpdated))
            appendLine(
                getString(R.string.change_domain_result_chapter_book, result.chapterBookUrlUpdated)
            )
            appendLine(getString(R.string.change_domain_result_chapter_url, result.chapterUrlUpdated))
            appendLine(
                getString(R.string.change_domain_result_chapter_base, result.chapterBaseUrlUpdated)
            )
            appendLine(
                getString(
                    R.string.change_domain_result_chapter_resource,
                    result.chapterResourceUrlUpdated
                )
            )
            // 计划数与实际数对照：预览有命中但实际没更新必须显式报告，不能当成功展示
            result.planned?.let { planned ->
                appendLine(
                    getString(R.string.change_domain_result_planned_total, planned.total)
                )
                if (result.countMismatches.isNotEmpty()) {
                    appendLine(getString(R.string.change_domain_result_mismatch))
                    result.countMismatches.take(MAX_RESULT_DETAIL_LINES).forEach {
                        appendLine("· $it")
                    }
                }
            }
            if (result.remainingHits >= 0) {
                appendLine(getString(R.string.change_domain_result_remaining, result.remainingHits))
            }
            if (result.cacheFolderRenamed > 0 || result.cacheFolderFailed > 0) {
                appendLine()
                append(
                    getString(
                        R.string.change_domain_result_cache,
                        result.cacheFolderRenamed,
                        result.cacheFolderFailed
                    )
                )
            }
            if (result.readerRefreshedBooks.isNotEmpty()) {
                appendLine()
                appendLine()
                append(
                    getString(
                        R.string.change_domain_result_reader,
                        result.readerRefreshedBooks.joinToString("、")
                    )
                )
            }
            if (result.readerRefreshFailedBooks.isNotEmpty()) {
                appendLine()
                appendLine()
                append(
                    getString(
                        R.string.change_domain_result_reader_failed,
                        result.readerRefreshFailedBooks.joinToString("、")
                    )
                )
            }
        }
        alert(
            title = getString(R.string.change_domain_result_title),
            message = message
        ) {
            neutralButton(getString(R.string.copy_text)) { sendToClip(message) }
            okButton()
        }
    }

    companion object {
        private const val MAX_RESULT_DETAIL_LINES = 8
    }

}