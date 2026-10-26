package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.core.view.indices
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.DialogDomainReplaceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.BookshelfFilter
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.manage.DomainReplaceCheckResult
import io.legado.app.ui.book.manage.DomainReplacePreview
import io.legado.app.ui.book.manage.DomainReplaceResult
import io.legado.app.ui.book.manage.DomainReplaceViewModel
import io.legado.app.ui.book.manage.RegexSymbolBar
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    /** 批量替换域名：全库维护操作，不依赖任何书籍选择状态 */
    private val domainReplaceViewModel by viewModels<DomainReplaceViewModel>()

    private val importBookshelf = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(requireContext())?.let { text ->
                viewModel.importBookshelf(text, groupId)
            }
        }.onFailure {
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
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
                    requireContext().sendToClip(uri.toString())
                }
            }
        }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private val waitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.addBookJob?.cancel()
            }
        }
    }

    abstract fun gotoTop()

    /**
     * 书架高级筛选:只改变展示条件,不修改任何书籍数据
     */
    private fun showBookshelfFilter() {
        val filters = BookshelfFilter.entries
        requireContext().selector(
            getString(R.string.filter),
            filters.map { getString(it.titleRes) }
        ) { _, index ->
            if (AppConfig.bookshelfFilter != index) {
                AppConfig.bookshelfFilter = index
                postEvent(EventBus.BOOKSHELF_FILTER_CHANGED, "")
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_bookshelf, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_search -> startActivity<SearchActivity>()
            R.id.menu_filter -> showBookshelfFilter()
            R.id.menu_update_toc -> activityViewModel.upToc(books)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showAddBookByUrlAlert()
            R.id.menu_bookshelf_manage -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_change_domain -> showDomainReplaceDialog()

            R.id.menu_download -> startActivity<CacheActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_export_bookshelf -> viewModel.exportBookshelf(books) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData("bookshelf.json", file, "application/json")
                }
            }

            R.id.menu_import_bookshelf -> importBookshelfAlert(groupId)
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    override fun observeLiveBus() {
        viewModel.addBookProgressLiveData.observe(this) { count ->
            if (count < 0) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText("添加中... ($count)")
            }
        }
        domainReplaceViewModel.domainReplaceBusy.observe(this) {
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
        domainReplaceViewModel.domainReplaceProcessLiveData.observe(this) {
            waitDialog.setText(it)
        }
        domainReplaceViewModel.domainReplaceCheckLiveData.observe(this) {
            if (it == null) return@observe
            domainReplaceViewModel.domainReplaceCheckLiveData.value = null
            when (it) {
                is DomainReplaceCheckResult.Blocked -> alertDomainReplaceBlocked(it.reason)
                is DomainReplaceCheckResult.Ready -> alertDomainReplacePreview(it.preview)
            }
        }
        domainReplaceViewModel.domainReplaceResultLiveData.observe(this) {
            if (it == null) return@observe
            domainReplaceViewModel.domainReplaceResultLiveData.value = null
            alertDomainReplaceResult(it)
        }
    }

    @SuppressLint("InflateParams")
    fun showAddBookByUrlAlert() {
        alert(titleResource = R.string.add_book_url) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    waitDialog.setText("添加中...")
                    waitDialog.show()
                    viewModel.addBookByUrl(it)
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        alert(titleResource = R.string.bookshelf_layout) {
            var bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            val alertBinding =
                DialogBookshelfConfigBinding.inflate(layoutInflater)
                    .apply {
                        if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                            AppConfig.bookGroupStyle = 0
                        }
                        if (bookshelfLayout !in rgLayout.indices) {
                            bookshelfLayout = 0
                            AppConfig.bookshelfLayout = 0
                        }
                        if (bookshelfSort !in rgSort.indices) {
                            bookshelfSort = 0
                            AppConfig.bookshelfSort = 0
                        }
                        spGroupStyle.setSelection(AppConfig.bookGroupStyle)
                        swShowUnread.isChecked = AppConfig.showUnread
                        swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
                        swShowWaitUpBooks.isChecked = AppConfig.showWaitUpCount
                        swShowBookshelfFastScroller.isChecked = AppConfig.showBookshelfFastScroller
                        rgLayout.checkByIndex(bookshelfLayout)
                        rgSort.checkByIndex(bookshelfSort)
                    }
            customView { alertBinding.root }
            okButton {
                alertBinding.apply {
                    var notifyMain = false
                    var recreate = false
                    if (AppConfig.bookGroupStyle != spGroupStyle.selectedItemPosition) {
                        AppConfig.bookGroupStyle = spGroupStyle.selectedItemPosition
                        notifyMain = true
                    }
                    if (AppConfig.showUnread != swShowUnread.isChecked) {
                        AppConfig.showUnread = swShowUnread.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showLastUpdateTime != swShowLastUpdateTime.isChecked) {
                        AppConfig.showLastUpdateTime = swShowLastUpdateTime.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showWaitUpCount != swShowWaitUpBooks.isChecked) {
                        AppConfig.showWaitUpCount = swShowWaitUpBooks.isChecked
                        activityViewModel.postUpBooksLiveData(true)
                    }
                    if (AppConfig.showBookshelfFastScroller != swShowBookshelfFastScroller.isChecked) {
                        AppConfig.showBookshelfFastScroller = swShowBookshelfFastScroller.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (bookshelfSort != rgSort.getCheckedIndex()) {
                        AppConfig.bookshelfSort = rgSort.getCheckedIndex()
                        upSort()
                    }
                    if (bookshelfLayout != rgLayout.getCheckedIndex()) {
                        AppConfig.bookshelfLayout = rgLayout.getCheckedIndex()
                        if (AppConfig.bookshelfLayout == 0) {
                            activityViewModel.booksGridRecycledViewPool.clear()
                        } else {
                            activityViewModel.booksListRecycledViewPool.clear()
                        }
                        recreate = true
                    }
                    if (recreate) {
                        postEvent(EventBus.RECREATE, "")
                    } else if (notifyMain) {
                        postEvent(EventBus.NOTIFY_MAIN, false)
                    }
                }
            }
            cancelButton()
        }
    }


    private fun importBookshelfAlert(groupId: Long) {
        alert(titleResource = R.string.import_bookshelf) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url/json"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    viewModel.importBookshelf(it, groupId)
                }
            }
            cancelButton()
            neutralButton(R.string.select_file) {
                importBookshelf.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        }
    }

    // ============================================================================================
    // 批量替换域名：入口在书架页右上菜单，不需要选中任何书籍，作用范围由域名规则与数据库决定
    // ============================================================================================

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
            domainReplaceViewModel.checkDomainReplace(oldInput, newInput)
        }
    }

    /** 旧域名输入框下方的正则快捷符号栏：点击把符号插入旧域名光标位置，不影响新域名输入框 */
    private fun upRegexSymbolBar(binding: DialogDomainReplaceBinding) {
        binding.llRegexSymbols.removeAllViews()
        RegexSymbolBar.symbols.forEach { symbol ->
            val key = AccentTextView(requireActivity(), null).apply {
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
                    domainReplaceViewModel.executeDomainReplace(rule.oldInput, rule.newDomain)
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
                neutralButton(getString(R.string.copy_text)) { requireContext().sendToClip(message) }
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
            neutralButton(getString(R.string.copy_text)) { requireContext().sendToClip(message) }
            okButton()
        }
    }

    companion object {
        private const val MAX_RESULT_DETAIL_LINES = 8
    }

}