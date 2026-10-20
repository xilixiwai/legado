package io.legado.app.ui.book.explore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 发现页调试链路固定测试:
 * 调试数据必须来自实际发现请求,并同时包含网络响应与解析统计。
 */
class ExploreDebugWiringTest {

    @Test
    fun `explore request captures actual response and parse stats`() {
        val webBook = source("app/src/main/java/io/legado/app/model/webBook/WebBook.kt")
        val explore = webBook.substringAfter("suspend fun exploreBookAwait")
            .substringBefore("private fun buildExploreDebugInfo")
        assertTrue(explore.contains("analyzeUrl.getStrResponseAwait()"))
        assertTrue(explore.contains("debugCallback?.let"))
        assertTrue(explore.contains("parseCallback = { parseStats = it }"))
        assertTrue(explore.contains("buildExploreDebugInfo("))

        val bookList = source("app/src/main/java/io/legado/app/model/webBook/BookList.kt")
        assertTrue(bookList.contains("parseCallback?.invoke("))
        assertTrue(bookList.contains("collectionCount = collections.size"))
        assertTrue(bookList.contains("resultCount = bookList.size"))

        val viewModel = source("app/src/main/java/io/legado/app/ui/book/explore/ExploreShowViewModel.kt")
        assertTrue(viewModel.contains("debugCallback = { debugInfo ->"))
        assertTrue(viewModel.contains("debugInfoLiveData.postValue(debugInfo)"))

        val activity = source("app/src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt")
        assertTrue(activity.contains("menu_explore_debug"))
        assertTrue(activity.contains("ExploreDebugFormatter.format(debugInfo)"))
    }

    private fun source(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path).readText().replace("\r\n", "\n")
    }
}
