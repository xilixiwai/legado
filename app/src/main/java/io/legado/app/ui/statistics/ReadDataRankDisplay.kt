package io.legado.app.ui.statistics

import io.legado.app.utils.cnCompare

/** Display-only operations: never merge rows or alter their persisted durations. */
internal fun List<ReadDataBookShow>.filterAndSortReadData(
    searchKey: String,
    sortMode: Int
): List<ReadDataBookShow> {
    val matches = filter { it.bookName.contains(searchKey, ignoreCase = true) }
    return when (sortMode) {
        0 -> matches.sortedWith { left, right -> left.bookName.cnCompare(right.bookName) }
        2 -> matches.sortedByDescending { it.lastRead }
        else -> matches.sortedByDescending { it.readTime }
    }
}
