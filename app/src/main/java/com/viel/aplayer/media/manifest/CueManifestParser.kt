package com.viel.aplayer.media.manifest

import com.viel.aplayer.library.ChapterCandidate
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.MetadataSuggestion
import com.viel.aplayer.logger.SecureLog
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Implements standard CUE sheet tag decoding.
 */
object CueManifestParser {

    data class CueResult(
        val metadata: MetadataSuggestion,
        val referencedFiles: List<String>,
        val chapters: List<ChapterCandidate>,
        val sidecarDescription: String? = null,
        val sidecarCoverFile: FileRef? = null
    )

    suspend fun parse(
        displayName: String,
        openStream: suspend () -> InputStream?,
        manifestFile: FileRef? = null,
        directoryContext: ManifestSidecarSupport.DirectoryContext = ManifestSidecarSupport.DirectoryContext(),
        openTextFile: (suspend (FileRef) -> InputStream?)? = null
    ): CueResult? {
        try {
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
                for (rawLine in lines) {
                    var line = rawLine.trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("\uFEFF")) line = line.substring(1).trim()

                    val parts = line.split(Regex("\\s+"), limit = 2)
                    val command = parts[0].uppercase()
                    val remainder = if (parts.size > 1) parts[1] else ""

                    when (command) {
                        "TITLE" -> {
                            val value = extractQuotedValue(remainder).limitManifestText()
                            if (!isTrackSection) {
                                globalTitle = value
                            } else {
                                currentTrackTitle = value
                            }
                        }
                        "PERFORMER" -> {
                            val value = extractQuotedValue(remainder).limitManifestText()
                            if (!isTrackSection) {
                                globalArtist = value
                            }
                        }
                        "COMPOSER", "NARRATOR" -> {
                            val value = extractQuotedValue(remainder).limitManifestText()
                            if (!isTrackSection) {
                                globalNarrator = value
                            }
                        }
                        "REM" -> {
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
                            val parsedFile = extractQuotedValue(remainder).limitManifestText()
                            currentFile = parsedFile
                            if (!files.addWithinManifestBudget(parsedFile)) break
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
                                    val accepted = chapterCandidates.addWithinManifestBudget(
                                        ChapterCandidate(
                                            title = currentTrackTitle ?: "Track ${chapterCandidates.size + 1}",
                                            fileKey = currentFile,
                                            fileOffsetMs = offsetMs
                                        )
                                    )
                                    if (!accepted) break
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

            val sidecarPayload = if (manifestFile != null && openTextFile != null) {
                ManifestSidecarSupport.resolveForManifest(
                    manifestFile = manifestFile,
                    directoryContext = directoryContext,
                    openTextFile = openTextFile
                )
            } else {
                ManifestSidecarSupport.SidecarPayload()
            }

            return CueResult(
                metadata = MetadataSuggestion(
                    title = globalTitle,
                    author = globalArtist,
                    narrator = globalNarrator,
                    year = globalYear,
                    description = globalDescription
                ),
                referencedFiles = files.distinct(),
                chapters = processedChapters,
                sidecarDescription = sidecarPayload.description,
                sidecarCoverFile = sidecarPayload.coverFile
            )
        } catch (e: Exception) {
            SecureLog.error("CueParser", "Error parsing CUE: $displayName", e)
            return null
        }
    }

    private fun extractQuotedValue(text: String): String {
        val start = text.indexOf('"')
        val end = text.lastIndexOf('"')
        return if (start != -1 && end != -1 && start < end) {
            text.substring(start + 1, end)
        } else {
            text.split(Regex("\\s+")).firstOrNull().orEmpty().trim()
        }
    }

    private fun applyRemMetadata(text: String): Pair<String, String>? {
        val parts = text.trim().split(Regex("\\s+"), limit = 2)
        val key = parts.getOrNull(0)?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        val value = parts.getOrNull(1)?.let { extractMetadataValue(it).limitManifestText() }.orEmpty()
        return key to value
    }

    private fun extractMetadataValue(text: String): String {
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
        val mins = (parts[0].toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val secs = (parts[1].toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val rawFrames = parts[2].toLongOrNull() ?: 0L
        val frames = rawFrames.coerceIn(0L, 74L)
        return (mins * 60 + secs) * 1000 + (frames * 1000 / 75)
    }

    private suspend fun detectCharset(openStream: suspend () -> InputStream?): Charset {
        return try {
            openStream()?.use { stream ->
                val bis = BufferedInputStream(stream)
                val buffer = ByteArray(4096)
                val read = bis.read(buffer)
                if (read <= 0) return Charsets.UTF_8

                if (read >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() && buffer[2] == 0xBF.toByte()) {
                    return Charsets.UTF_8
                }

                if (isValidUtf8(buffer, read)) Charsets.UTF_8 else Charset.forName("Shift-JIS")
            } ?: Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    private fun isValidUtf8(buffer: ByteArray, length: Int): Boolean {
        var i = 0
        while (i < length) {
            val c = buffer[i].toInt() and 0xFF
            if (c < 0x80) { i++; continue }
            val n = when (c) {
                in 0xC2..0xDF -> 1
                in 0xE0..0xEF -> 2
                in 0xF0..0xF4 -> 3
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
