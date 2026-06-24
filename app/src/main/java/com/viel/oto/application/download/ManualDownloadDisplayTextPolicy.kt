package com.viel.oto.application.download

object ManualDownloadDisplayTextPolicy {
    fun progressBookLabel(progressPercent: Int, author: String, bookTitle: String): String {
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
        val compactStatus = statusText.trim()
        val compactFileProgress = "${completedFiles.coerceAtLeast(0)}/${totalFiles.coerceAtLeast(completedFiles.coerceAtLeast(0))}"
        val compactByteProgress = byteProgressLabel(downloadedSizeText, totalSizeText)
        return listOf(compactStatus, compactFileProgress, compactByteProgress)
            .filter { segment -> segment.isNotEmpty() }
            .joinToString(SUPPLEMENTAL_SEPARATOR)
    }

    private fun byteProgressLabel(downloadedSizeText: String?, totalSizeText: String?): String {
        val compactDownloaded = downloadedSizeText?.trim().orEmpty()
        val compactTotal = totalSizeText?.trim().orEmpty()
        return if (compactDownloaded.isEmpty() || compactTotal.isEmpty()) {
            ""
        } else {
            "$compactDownloaded/$compactTotal"
        }
    }

    private fun String.compactForManualDownloadLabel(maxChars: Int): String {
        val normalized = trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.take(maxChars - TRUNCATION_MARKER.length).trimEnd() + TRUNCATION_MARKER
    }

    private const val AUTHOR_MAX_CHARS = 12
    private const val TITLE_MAX_CHARS = 48
    private const val SUPPLEMENTAL_SEPARATOR = " · "
    private const val TRUNCATION_MARKER = "..."
}
