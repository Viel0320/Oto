package com.viel.aplayer.library.manifest

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import com.viel.aplayer.library.ChapterCandidate
import com.viel.aplayer.library.MetadataSuggestion

/**
 * 工业级 CUE 标准解析器。
 */
object CueManifestParser {

    data class CueResult(
        val metadata: MetadataSuggestion,
        val referencedFiles: List<String>,
        val chapters: List<ChapterCandidate>
    )

    suspend fun parse(displayName: String, openStream: suspend () -> InputStream?): CueResult? {
        try {
            // 为每一次改动添加详尽的中文注释：CUE 解析器只接收 VFS 流工厂，不再知道来源原生文件对象或 ContentResolver。
            val charset = detectCharset(openStream)
            val inputStream = openStream() ?: return null
            val reader = BufferedReader(InputStreamReader(inputStream, charset))

            var globalTitle: String? = null
            var globalArtist: String? = null
            var globalNarrator: String? = null
            var globalYear: String? = null
            var globalDescription: String? = null
            
            val files = mutableListOf<String>()
            val chapterCandidates = mutableListOf<ChapterCandidate>()
            
            var currentFile: String? = null
            var currentTrackTitle: String? = null
            var isTrackSection = false

            reader.useLines { lines ->
                lines.forEach { rawLine ->
                    var line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    if (line.startsWith("\uFEFF")) line = line.substring(1).trim()

                    val parts = line.split(Regex("\\s+"), limit = 2)
                    val command = parts[0].uppercase()
                    val remainder = if (parts.size > 1) parts[1] else ""

                    when (command) {
                        "TITLE" -> {
                            val value = extractQuotedValue(remainder)
                            if (!isTrackSection) {
                                globalTitle = value
                            } else {
                                currentTrackTitle = value
                            }
                        }
                        "PERFORMER" -> {
                            val value = extractQuotedValue(remainder)
                            if (!isTrackSection) {
                                globalArtist = value
                            }
                        }
                        "COMPOSER", "NARRATOR" -> {
                            // Book-level CUE narrator fields win before falling back to the first audio file.
                            val value = extractQuotedValue(remainder)
                            if (!isTrackSection) {
                                globalNarrator = value
                            }
                        }
                        "REM" -> {
                            // REM carries common CUE metadata such as DATE/YEAR/COMMENT without affecting tracks.
                            applyRemMetadata(remainder)?.let { (key, value) ->
                                when (key) {
                                    "AUTHOR", "ARTIST" -> if (!isTrackSection) globalArtist = value
                                    "COMPOSER", "NARRATOR", "READER" -> if (!isTrackSection) globalNarrator = value
                                    "DATE", "YEAR" -> if (!isTrackSection) globalYear = value
                                    "COMMENT", "DESCRIPTION", "SUMMARY" -> if (!isTrackSection) globalDescription = value
                                }
                            }
                        }
                        "FILE" -> {
                            currentFile = extractQuotedValue(remainder)
                            // FILE always yields a parsed path here.
                            files.add(currentFile)
                        }
                        "TRACK" -> {
                            isTrackSection = true
                            currentTrackTitle = null
                        }
                        "INDEX" -> {
                            val indexParts = remainder.split(Regex("\\s+"))
                            if (indexParts.size >= 2 && indexParts[0] == "01") {
                                val timeStr = indexParts[1]
                                val offsetMs = parseCueTime(timeStr)
                                if (currentFile != null) {
                                    chapterCandidates.add(ChapterCandidate(
                                        title = currentTrackTitle ?: "Track ${chapterCandidates.size + 1}",
                                        fileKey = currentFile,
                                        fileOffsetMs = offsetMs
                                    ))
                                }
                            }
                        }
                    }
                }
            }

            val processedChapters = mutableListOf<ChapterCandidate>()
            for (i in chapterCandidates.indices) {
                val current = chapterCandidates[i]
                val duration = if (i < chapterCandidates.size - 1 && chapterCandidates[i + 1].fileKey == current.fileKey) {
                    chapterCandidates[i + 1].fileOffsetMs - current.fileOffsetMs
                } else 0L
                processedChapters.add(current.copy(durationMs = duration))
            }

            return CueResult(
                // CUE global metadata is persisted first; missing fields are filled later by ImportOrchestrator.
                metadata = MetadataSuggestion(
                    title = globalTitle,
                    author = globalArtist,
                    narrator = globalNarrator,
                    year = globalYear,
                    description = globalDescription
                ),
                referencedFiles = files.distinct(),
                chapters = processedChapters
            )
        } catch (e: Exception) {
            Log.e("CueParser", "Error parsing CUE: $displayName", e)
            return null
        }
    }

    private fun extractQuotedValue(text: String): String {
        val start = text.indexOf('"')
        val end = text.lastIndexOf('"')
        return if (start != -1 && end != -1 && start < end) {
            text.substring(start + 1, end)
        } else {
            // FILE commands need the first token when values are unquoted, e.g. FILE album.wav WAVE.
            text.split(Regex("\\s+")).firstOrNull().orEmpty().trim()
        }
    }

    private fun applyRemMetadata(text: String): Pair<String, String>? {
        // REM is "KEY value"; for comments we keep the whole unquoted value instead of only one token.
        val parts = text.trim().split(Regex("\\s+"), limit = 2)
        val key = parts.getOrNull(0)?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        val value = parts.getOrNull(1)?.let { extractMetadataValue(it) }.orEmpty()
        return key to value
    }

    private fun extractMetadataValue(text: String): String {
        // Metadata fields can contain spaces even when the CUE file does not quote them.
        val start = text.indexOf('"')
        val end = text.lastIndexOf('"')
        return if (start != -1 && end != -1 && start < end) {
            text.substring(start + 1, end)
        } else {
            text.trim()
        }
    }

    private fun parseCueTime(timeStr: String): Long {
        val parts = timeStr.split(':')
        if (parts.size != 3) return 0L
        val mins = parts[0].toLongOrNull() ?: 0L
        val secs = parts[1].toLongOrNull() ?: 0L
        val frames = parts[2].toLongOrNull() ?: 0L
        return (mins * 60 + secs) * 1000 + (frames * 1000 / 75)
    }

    private suspend fun detectCharset(openStream: suspend () -> InputStream?): Charset {
        return try {
            openStream()?.use { stream ->
                val bis = BufferedInputStream(stream)
                val buffer = ByteArray(4096)
                val read = bis.read(buffer)
                if (read <= 0) return Charsets.UTF_8
                
                // 检查 BOM
                if (read >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() && buffer[2] == 0xBF.toByte()) {
                    return Charsets.UTF_8
                }
                
                // 简单的启发式检测：检查是否包含有效的 UTF-8 字节序列
                // 如果检测失败，针对你的场景，默认回退到日文编码 Shift-JIS
                if (isValidUtf8(buffer, read)) Charsets.UTF_8 else Charset.forName("Shift-JIS")
            } ?: Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    private fun isValidUtf8(buffer: ByteArray, length: Int): Boolean {
        var i = 0
        while (i < length) {
            val c = buffer[i].toInt() and 0xFF
            if (c < 0x80) { i++; continue }
            val n = when {
                c in 0xC2..0xDF -> 1
                c in 0xE0..0xEF -> 2
                c in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + n >= length) break
            for (j in 1..n) {
                if (buffer[i + j].toInt() and 0xC0 != 0x80) return false
            }
            i += n + 1
        }
        return true
    }
}
