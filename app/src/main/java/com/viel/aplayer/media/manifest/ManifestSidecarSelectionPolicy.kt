package com.viel.aplayer.media.manifest

import com.viel.aplayer.library.FileRef
import java.util.Locale

/**
 * Centralizes sibling text and cover candidate ranking.
 * Keeps deterministic sidecar choice rules separate from text-file I/O, charset decoding, and manifest parsing.
 */
object ManifestSidecarSelectionPolicy {
    /**
     * Ranks same-name, common-name, and single-file description candidates.
     * Manifest parsers can request strict same-name matching, while heuristic imports can still fall back to common metadata names or the only text file in a directory.
     */
    fun selectTextDescription(
        textFiles: List<FileRef>,
        baseName: String? = null,
        strictSameNameOnly: Boolean = false
    ): FileRef? {
        if (textFiles.isEmpty()) return null

        if (!baseName.isNullOrBlank()) {
            val sameNameFile = textFiles.firstOrNull { file ->
                file.displayName.substringBeforeLast('.', missingDelimiterValue = "").equals(baseName, ignoreCase = true)
            }
            if (sameNameFile != null) return sameNameFile
        }

        if (strictSameNameOnly) return null

        val commonNameFile = textFiles.firstOrNull { file ->
            val nameWithoutExt = file.displayName.substringBeforeLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            COMMON_TEXT_NAMES.any { common -> nameWithoutExt == common || nameWithoutExt.contains(common) }
        }
        if (commonNameFile != null) return commonNameFile

        return textFiles.singleOrNull()
    }

    /**
     * Prefers conventional artwork filenames before generic image fallback.
     * Parser and recovery paths share the same cover ranking so rescans do not choose different nearby artwork for the same directory.
     */
    fun selectDirectoryCover(imageFiles: List<FileRef>): FileRef? =
        imageFiles.firstOrNull { image ->
            val baseName = image.displayName.substringBeforeLast('.').lowercase(Locale.ROOT)
            baseName in PRIORITY_COVER_NAMES
        } ?: imageFiles.firstOrNull()

    private val COMMON_TEXT_NAMES = listOf("desc", "description", "info", "book", "readme", "\u7b80\u4ecb", "\u6709\u58f0\u4e66\u7b80\u4ecb")
    private val PRIORITY_COVER_NAMES = listOf("cover", "folder", "artwork", "front")
}
