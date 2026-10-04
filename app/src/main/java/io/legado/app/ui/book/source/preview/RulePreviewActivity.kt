package io.legado.app.ui.book.source.preview

import android.os.Bundle
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRulePreviewBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 书源规则实时预览:使用书源编辑页当前(未保存)的正文规则解析本地测试 HTML。
 */
class RulePreviewActivity :
    VMBaseActivity<ActivityRulePreviewBinding, RulePreviewViewModel>() {

    override val binding by viewBinding(ActivityRulePreviewBinding::inflate)
    override val viewModel by viewModels<RulePreviewViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initSource(intent.getStringExtra("source"))
        binding.tvJsHint.text = getString(R.string.preview_js_hint)
        binding.btnPreview.setOnClickListener {
            viewModel.previewContent(
                html = binding.etTestHtml.text?.toString() ?: "",
                onLoading = {
                    binding.tvStatus.text = getString(R.string.preview_running)
                    binding.tvResult.text = ""
                    binding.btnPreview.isEnabled = false
                },
                onSuccess = { result ->
                    binding.tvStatus.text = getString(R.string.preview_done)
                    binding.tvResult.text = result
                    binding.btnPreview.isEnabled = true
                },
                onEmpty = {
                    binding.tvStatus.text = getString(R.string.preview_empty)
                    binding.tvResult.text = ""
                    binding.btnPreview.isEnabled = true
                },
                onError = { msg ->
                    binding.tvStatus.text = getString(R.string.preview_error)
                    binding.tvResult.text = msg
                    binding.btnPreview.isEnabled = true
                }
            )
        }
    }

}
