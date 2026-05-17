package com.viel.aplayer.library.manifest

import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.library.ClaimSource
import com.viel.aplayer.library.ClaimSourceType

/**
 * 启发式 M3U8 建议器。
 */
object HeuristicM3u8Suggester {

    /**
     * 对未被占用的音频文件进行分组建议。
     */
    fun suggest(rootId: String, directory: DocumentFile, files: List<DocumentFile>): List<ClaimSource> {
        if (files.size < 2) return emptyList()

        val sortedFiles = files.sortedBy { it.name?.lowercase() }
        val commonPrefix = findCommonPrefix(
            sortedFiles.first().name ?: "", 
            sortedFiles.last().name ?: ""
        ).trim()

        val shouldAggregate = when {
            files.size >= 3 && commonPrefix.length >= 3 -> true
            isStrictlySequential(sortedFiles) -> true
            else -> false
        }

        if (!shouldAggregate) return emptyList()

        return listOf(ClaimSource(
            type = ClaimSourceType.GENERATED_M3U8,
            sourceUri = "heuristic://${directory.uri}",
            rootId = rootId,
            priority = 5,
            referencedFileUris = sortedFiles.map { it.uri.toString() },
            fileTitles = sortedFiles.associate { it.uri.toString() to (it.name?.substringBeforeLast(".") ?: "") },
            displayName = commonPrefix.ifBlank { directory.name ?: "Aggregated Collection" },
            parentUri = directory.uri.toString()
        ))
    }

    private fun findCommonPrefix(s1: String, s2: String): String {
        var i = 0
        while (i < s1.length && i < s2.length && s1[i] == s2[i]) {
            i++
        }
        return s1.substring(0, i).replace(Regex("[0-9\\s\\-_]+$"), "")
    }

    private fun isStrictlySequential(files: List<DocumentFile>): Boolean {
        val numberRegex = Regex("(\\d+)")
        val sequence = files.mapNotNull { 
            numberRegex.findAll(it.name ?: "").lastOrNull()?.value?.toIntOrNull() 
        }
        if (sequence.size != files.size) return false
        for (i in 0 until sequence.size - 1) {
            if (sequence[i+1] <= sequence[i]) return false
        }
        return true
    }
}
