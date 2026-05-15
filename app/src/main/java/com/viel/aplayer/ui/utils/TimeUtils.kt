package com.viel.aplayer.ui.utils

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

fun formatCompactDuration(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

fun formatFileSize(sizeInBytes: Long): String {
    if (sizeInBytes <= 0) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(sizeInBytes.toDouble()) / log10(1024.0))
        .toInt()
        .coerceAtMost(units.lastIndex)
    return String.format(
        Locale.getDefault(),
        "%.1f %s",
        sizeInBytes / 1024.0.pow(digitGroups.toDouble()),
        units[digitGroups]
    )
}

fun formatDate(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}

//fun formatShortDate(ms: Long): String {
//    val sdf = java.text.SimpleDateFormat("MM-dd", Locale.getDefault())
//    return sdf.format(java.util.Date(ms))
//}
