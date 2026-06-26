package com.viel.oto.shared.policy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Formats playback positions as a fixed-width clock value for shared UI surfaces.
 */
fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * Formats longer durations as a compact hour/minute summary for dense list rows.
 */
fun formatCompactDuration(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/**
 * Formats byte counts with binary units while keeping the display stable across callers.
 */
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

/**
 * Formats timestamps with the app-wide short date-time pattern used in shared dialogs.
 */
fun formatDate(ms: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}
