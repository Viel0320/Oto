package com.viel.aplayer.application.download

object ManualDownloadDisplayTextPolicy {
    fun progressBookLabel(progressPercent: Int, author: String, bookTitle: String): String {
        // Manual Download Progress Label (Place percentage before compact book identity)
        // Download notifications and the management list share this formatter so long author handling stays consistent across surfaces.
        val compactTitle = bookTitle.ifBlank { author }.compactForManualDownloadLabel(TITLE_MAX_CHARS)
        val compactAuthor = author.trim()
            .takeIf { value -> value.isNotEmpty() }
            ?.compactForManualDownloadLabel(AUTHOR_MAX_CHARS)
        return if (compactAuthor == null) {
            "${progressPercent.coerceIn(0, 100)}%   $compactTitle"
        } else {
            "${progressPercent.coerceIn(0, 100)}%   $compactAuthor - $compactTitle"
        }
    }

    fun taskSupplementalLabel(
        statusText: String,
        completedFiles: Int,
        totalFiles: Int,
        downloadedSizeText: String? = null,
        totalSizeText: String? = null
    ): String {
        // Manual Download Supplemental Label (Compose compact task details without repeating title metadata)
        // File counts are numeric-only and byte progress is optional so notifications and management rows share the same dense status copy.
        val compactStatus = statusText.trim()
        val compactFileProgress = "${completedFiles.coerceAtLeast(0)}/${totalFiles.coerceAtLeast(completedFiles.coerceAtLeast(0))}"
        val compactByteProgress = byteProgressLabel(downloadedSizeText, totalSizeText)
        return listOf(compactStatus, compactFileProgress, compactByteProgress)
            .filter { segment -> segment.isNotEmpty() }
            .joinToString(SUPPLEMENTAL_SEPARATOR)
    }

    private fun byteProgressLabel(downloadedSizeText: String?, totalSizeText: String?): String {
        // Manual Download Size Segment (Only show byte progress when both sides of the transfer range are known)
        // A partial size label would look like another file count, so incomplete byte metadata is omitted from the supplemental copy.
        val compactDownloaded = downloadedSizeText?.trim().orEmpty()
        val compactTotal = totalSizeText?.trim().orEmpty()
        return if (compactDownloaded.isEmpty() || compactTotal.isEmpty()) {
            ""
        } else {
            "$compactDownloaded/$compactTotal"
        }
    }

    private fun String.compactForManualDownloadLabel(maxChars: Int): String {
        // Manual Download Text Compaction (Preserve the title when author metadata is unusually long)
        // SystemUI and Compose still ellipsize final rows, but field-level compaction keeps both metadata parts visible first.
        val normalized = trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.take(maxChars - TRUNCATION_MARKER.length).trimEnd() + TRUNCATION_MARKER
    }

    private const val AUTHOR_MAX_CHARS = 12
    private const val TITLE_MAX_CHARS = 48
    private const val SUPPLEMENTAL_SEPARATOR = " · "
    private const val TRUNCATION_MARKER = "..."
}
