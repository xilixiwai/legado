package io.legado.app.ui.book.manage

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.book.DomainReplaceConflicts.ConflictItem
import io.legado.app.help.book.DomainReplaceConflicts.ConflictKind
import io.legado.app.help.book.DomainReplaceConflicts.ConflictStatus
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity

/**
 * 「批量替换域名」冲突清单：可持续操作的独立列表，不是一次性提示。
 *
 * - 每条冲突展示新旧记录的稳定 ID（bookUrl）、分类、状态与建议处理方式；
 * - 「查看书籍」进入书籍详情，返回时本弹窗仍在：清单、状态与滚动位置由
 *   DialogFragment + ViewModel 保留，仅重读相关记录刷新状态，不重新扫描全库；
 * - 「清理旧记录」仅对 A1（已确认同一本书）开放，且需二次确认删除影响；
 * - 处理完某条后可对单项「重新检查」，冲突消失时直接完成该书替换。
 */
class DomainReplaceConflictDialog : DialogFragment() {

    companion object {
        fun newInstance() = DomainReplaceConflictDialog()

        fun kindLabel(context: android.content.Context, kind: ConflictKind): String = context.getString(
            when (kind) {
                ConflictKind.SAME_BOOK_CONFIRMED -> R.string.change_domain_kind_same_book
                ConflictKind.SAME_BOOK_UNCONFIRMED -> R.string.change_domain_kind_same_book_unconfirmed
                ConflictKind.SAME_NAME_DIFF_SOURCE -> R.string.change_domain_kind_diff_source
                ConflictKind.DIFF_BOOK -> R.string.change_domain_kind_diff_book
                ConflictKind.TARGET_IS_LOCAL -> R.string.change_domain_kind_target_local
                ConflictKind.BATCH_TARGET_COLLISION -> R.string.change_domain_kind_batch_collision
            }
        )

        fun statusLabel(context: android.content.Context, status: ConflictStatus): String =
            context.getString(
                when (status) {
                    ConflictStatus.PENDING -> R.string.change_domain_status_pending
                    ConflictStatus.RESOLVED -> R.string.change_domain_status_resolved
                    ConflictStatus.SKIPPED -> R.string.change_domain_status_skipped
                    ConflictStatus.FAILED -> R.string.change_domain_status_failed
                }
            )

        /** 一行摘要：旧记录 → 目标记录 + 分类（预览弹窗与复制清单共用） */
        fun itemSummary(context: android.content.Context, item: ConflictItem): String {
            return context.getString(
                R.string.change_domain_conflict_item_line,
                item.oldBook.name,
                item.oldBookUrl,
                item.targetBook?.name ?: item.targetBookUrl ?: "-",
            ) + " " + kindLabel(context, item.kind)
        }
    }

    private val vm by lazy { (parentFragment as BaseBookshelfFragment).domainReplaceVm() }
    private lateinit var adapter: ConflictAdapter

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        adapter = ConflictAdapter(vm)
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DomainReplaceConflictDialog.adapter
            setPadding(0, dp(4), 0, 0)
            clipToPadding = false
        }
        vm.conflictItemsLiveData.observe(this) { items ->
            adapter.submit(items)
        }
        adapter.submit(vm.conflictItemsLiveData.value.orEmpty())
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.change_domain_conflict_list_title)
            .setView(recyclerView)
            .setPositiveButton(R.string.change_domain_close, null)
            .setNeutralButton(R.string.copy_text) { _, _ ->
                requireContext().sendToClip(conflictListText(adapter.current))
            }
            .create()
    }

    override fun onResume() {
        super.onResume()
        // 从书籍详情返回：清单与滚动位置保留，重读相关记录刷新状态
        adapter.submit(vm.conflictItemsLiveData.value.orEmpty())
    }

    private fun conflictListText(items: List<ConflictItem>): String {
        return buildString {
            appendLine(getString(R.string.change_domain_conflict_list_title))
            items.forEach { item ->
                appendLine(
                    "· ${itemSummary(requireContext(), item)} " +
                            "[${statusLabel(requireContext(), item.status)}] ${item.note.orEmpty()}"
                )
            }
        }
    }

    private fun kindLabel(kind: ConflictKind): String = kindLabel(requireContext(), kind)

    private fun statusLabel(status: ConflictStatus): String =
        statusLabel(requireContext(), status)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class ConflictAdapter(val vm: DomainReplaceViewModel) :
        RecyclerView.Adapter<ConflictAdapter.Holder>() {

        var current: List<ConflictItem> = emptyList()
            private set

        /** 只对内容变化的行做单项刷新，保留 RecyclerView 的滚动位置 */
        fun submit(items: List<ConflictItem>) {
            val old = current
            current = items
            val notifyCount = maxOf(items.size, old.size)
            for (i in 0 until notifyCount) {
                if (i >= old.size || i >= items.size || old[i] != items[i]) {
                    notifyItemChanged(i)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_domain_replace_conflict, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = current.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(current[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTitle = view.findViewById<TextView>(R.id.tv_title)
            private val tvKind = view.findViewById<TextView>(R.id.tv_kind)
            private val tvDetail = view.findViewById<TextView>(R.id.tv_detail)
            private val tvStatus = view.findViewById<TextView>(R.id.tv_status)
            private val btnViewOld = view.findViewById<Button>(R.id.btn_view_old)
            private val btnViewNew = view.findViewById<Button>(R.id.btn_view_new)
            private val btnRecheck = view.findViewById<Button>(R.id.btn_recheck)
            private val btnCleanup = view.findViewById<Button>(R.id.btn_cleanup)
            private val btnSkip = view.findViewById<Button>(R.id.btn_skip)

            fun bind(item: ConflictItem) {
                val targetName = item.targetBook?.name ?: item.targetBookUrl ?: "-"
                tvTitle.text = getString(
                    R.string.change_domain_conflict_item_line,
                    item.oldBook.name,
                    item.oldBookUrl,
                    targetName,
                )
                tvKind.text = kindLabel(item.kind)
                tvDetail.text = buildString {
                    append(
                        getString(
                            R.string.change_domain_conflict_detail_old,
                            item.oldBookUrl,
                            item.oldBook.chapterCount,
                            if (item.oldBook.hasProgress) {
                                getString(R.string.change_domain_conflict_has_progress)
                            } else {
                                getString(R.string.change_domain_conflict_no_progress)
                            },
                            item.oldBook.origin,
                        )
                    )
                    if (item.targetBook != null) {
                        appendLine()
                        append(
                            getString(
                                R.string.change_domain_conflict_detail_target,
                                item.targetBookUrl ?: "-",
                                item.targetBook.chapterCount,
                                if (item.targetBook.hasProgress) {
                                    getString(R.string.change_domain_conflict_has_progress)
                                } else {
                                    getString(R.string.change_domain_conflict_no_progress)
                                },
                                item.targetBook.origin,
                            )
                        )
                    }
                }
                tvStatus.text = "${statusLabel(item.status)} ${item.note.orEmpty()}".trim()
                btnViewOld.setOnClickListener {
                    requireActivity().startActivity<BookInfoActivity> {
                        putExtra("bookUrl", item.oldBookUrl)
                    }
                }
                btnViewNew.visibility =
                    if (item.targetBookUrl != null) View.VISIBLE else View.GONE
                btnViewNew.setOnClickListener {
                    item.targetBookUrl?.let { url ->
                        requireActivity().startActivity<BookInfoActivity> {
                            putExtra("bookUrl", url)
                        }
                    }
                }
                btnRecheck.setOnClickListener { vm.recheckConflict(item) }
                btnCleanup.visibility = if (item.canCleanupOld) View.VISIBLE else View.GONE
                btnCleanup.setOnClickListener { confirmCleanup(item) }
                btnSkip.isEnabled = item.status == ConflictStatus.PENDING
                btnSkip.setOnClickListener { vm.skipConflict(item) }
            }

            private fun confirmCleanup(item: ConflictItem) {
                val target = item.targetBook ?: return
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_domain_cleanup_confirm_title)
                    .setMessage(
                        getString(
                            R.string.change_domain_cleanup_confirm,
                            item.oldBook.name,
                            item.oldBook.chapterCount,
                            item.oldBookUrl,
                            target.name,
                        )
                    )
                    .setPositiveButton(R.string.ok) { _, _ -> vm.cleanupOldRecord(item) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }
}
