package com.viel.oto.media.manifest

import com.viel.oto.library.FileRef
import com.viel.oto.library.MetadataSuggestion
import com.viel.oto.logger.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Implements standard M3U/M3U8 playlist sheet tag parsing.
 */
object M3u8ManifestParser {

    data class M3u8Item(
        val uri: String,
        val title: String?,
        val durationMs: Long?
    )

    data class M3u8Result(
        val metadata: MetadataSuggestion,
        val items: List<M3u8Item>,
        val sidecarDescription: String? = null,
        val sidecarCoverFile: FileRef? = null
    )

    suspend fun parse(
        displayName: String,
        openStream: suspend () -> InputStream?,
        manifestFile: FileRef? = null,
        directoryContext: ManifestSidecarSupport.DirectoryContext = ManifestSidecarSupport.DirectoryContext(),
        openTextFile: (suspend (FileRef) -> InputStream?)? = null
    ): M3u8Result {
        val items = mutableListOf<M3u8Item>()
        var playlistTitle: String? = null
        var playlistAuthor: String? = null
        var playlistNarrator: String? = null
        var playlistYear: String? = null
        var playlistDescription: String? = null
        val sidecarPayload = if (manifestFile != null && openTextFile != null) {
            ManifestSidecarSupport.resolveForManifest(
                manifestFile = manifestFile,
                directoryContext = directoryContext,
                openTextFile = openTextFile
            )
        } else {
            ManifestSidecarSupport.SidecarPayload()
        }
        try {
            val inputStream = openStream() ?: return M3u8Result(MetadataSuggestion(), emptyList())
            val reader = BufferedReader(withContext(Dispatchers.IO) {
                InputStreamReader(inputStream, "UTF-8")
            })

            var currentTitle: String? = null
            var currentDurationMs: Long? = null

            reader.useLines { lines ->
                for (rawLine in lines) {
                    var line = rawLine.trim()
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1).trim()
                    }
                    if (line.isBlank()) continue

                    if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                        val content = line.substring(8)
                        val commaIndex = content.indexOf(',')
                        if (commaIndex != -1) {
                            val durPart = content.substring(0, commaIndex).trim()
                            currentDurationMs = durPart.toDoubleOrNull()?.let { (it * 1000).toLong() }
                            currentTitle = content.substring(commaIndex + 1).limitManifestText()
                        }
                    } else if (line.startsWith("#")) {
                        parseMetadataLine(line)?.let { (key, value) ->
                            when (key) {
                                "PLAYLIST", "EXTM3U-TITLE", "TITLE", "EXTALB", "ALBUM" -> playlistTitle = value
                                "EXTART", "ARTIST", "AUTHOR" -> playlistAuthor = value
                                "COMPOSER", "NARRATOR", "READER" -> playlistNarrator = value
                                "YEAR", "DATE" -> playlistYear = value
                                "DESCRIPTION", "SUMMARY", "COMMENT" -> playlistDescription = value
                            }
                        }
                    } else if (!line.startsWith("#")) {
                        if (!line.startsWith("http://", ignoreCase = true) &&
                            !line.startsWith("https://", ignoreCase = true)) {
                            val accepted = items.addWithinManifestBudget(
                                M3u8Item(
                                    uri = line.limitManifestText(),
                                    title = currentTitle,
                                    durationMs = currentDurationMs
                                )
                            )
                            if (!accepted) break
                        }
                        currentTitle = null
                        currentDurationMs = null
                    }
                }
            }
        } catch (e: Exception) {
            SecureLog.error("M3u8Parser", "Failed to parse M3U8: $displayName", e)
        }
        return M3u8Result(
            metadata = MetadataSuggestion(
                title = playlistTitle,
                author = playlistAuthor,
                narrator = playlistNarrator,
                year = playlistYear,
                description = playlistDescription
            ),
            items = items,
            sidecarDescription = sidecarPayload.description,
            sidecarCoverFile = sidecarPayload.coverFile
        )
    }

    private fun parseMetadataLine(line: String): Pair<String, String>? {
        val content = line.removePrefix("#").trim()
        val separatorIndex = listOf(content.indexOf(':'), content.indexOf('='))
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        val key = content.take(separatorIndex).trim().uppercase()
        val value = content.drop(separatorIndex + 1).trim().trim('"').limitManifestText()
        if (key.isBlank() || value.isBlank()) return null
        return key to value
    }
}
