package io.legado.app.ui.book.source.preview

import android.app.Application
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.BookSource
import io.legado.app.model.RulePreview
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

class RulePreviewViewModel(application: Application) : BaseViewModel(application) {

    var bookSource: BookSource? = null

    fun initSource(sourceJson: String?) {
        bookSource = sourceJson?.let {
            GSON.fromJsonObject<BookSource>(it).getOrNull()
        }
    }

    fun previewContent(
        html: String,
        onLoading: () -> Unit,
        onSuccess: (String) -> Unit,
        onEmpty: () -> Unit,
        onError: (String) -> Unit
    ) {
        val source = bookSource ?: run {
            onError.invoke(context.getString(R.string.preview_source_invalid))
            return
        }
        execute {
            RulePreview.previewContent(source, html)
        }.onStart {
            onLoading.invoke()
        }.onSuccess { result ->
            if (result.isBlank()) {
                onEmpty.invoke()
            } else {
                onSuccess.invoke(result)
            }
        }.onError {
            onError.invoke("${it::class.java.simpleName}: ${it.message ?: "unknown"}")
        }
    }
}
