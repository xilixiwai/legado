package io.legado.app.utils

import kotlin.math.abs

fun Long.toTimeAgo(): String {
    val curTime = System.currentTimeMillis()
    val time = this
    val seconds = abs(System.currentTimeMillis() - time) / 1000f
    val end = if (time < curTime) "前" else "后"

    val start = when {
        seconds < 60 -> "${seconds.toInt()}秒"
        seconds < 3600 -> {
            val minutes = seconds / 60f
            "${minutes.toInt()}分钟"
        }
        seconds < 86400 -> {
            val hours = seconds / 3600f
            "${hours.toInt()}小时"
        }
        seconds < 604800 -> {
            val days = seconds / 86400f
            "${days.toInt()}天"
        }
        seconds < 2_628_000 -> {
            val weeks = seconds / 604800f
            "${weeks.toInt()}周"
        }
        seconds < 31_536_000 -> {
            val months = seconds / 2_628_000f
            "${months.toInt()}月"
        }
        else -> {
            val years = seconds / 31_536_000f
            "${years.toInt()}年"
        }
    }
    return start + end
}

fun Int.toDurationTime(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * 把毫秒时长格式化为“d天h小时m分钟s秒”。
 *
 * 该实现原为 [io.legado.app.ui.about.ReadRecordActivity.formatDuring] 的私有逻辑,
 * 抽取为共享纯函数以避免重复实现;输出与抽取前完全一致(全为 0 时返回“0秒”)。
 */
fun Long.toReadDuration(): String {
    val days = this / (1000 * 60 * 60 * 24)
    val hours = this % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
    val minutes = this % (1000 * 60 * 60) / (1000 * 60)
    val seconds = this % (1000 * 60) / 1000
    val d = if (days > 0) "${days}天" else ""
    val h = if (hours > 0) "${hours}小时" else ""
    val m = if (minutes > 0) "${minutes}分钟" else ""
    val s = if (seconds > 0) "${seconds}秒" else ""
    var time = "$d$h$m$s"
    if (time.isBlank()) {
        time = "0秒"
    }
    return time
}
