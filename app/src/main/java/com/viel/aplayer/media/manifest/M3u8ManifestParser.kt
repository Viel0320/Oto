package com.viel.aplayer.media.manifest

import android.util.Log
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.MetadataSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * M3U8 Playlist Parser (Implements standard M3U/M3U8 playlist sheet tag parsing)
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
        // Sidecar Resolution (Collect nearby txt and cover assets inside parser boundary)
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
            // Coordinated Sidecar Search (Resolve local asset files alongside parsing layout)
            ManifestSidecarSupport.resolveForManifest(
                manifestFile = manifestFile,
                directoryContext = directoryContext,
                openTextFile = openTextFile
            )
        } else {
            ManifestSidecarSupport.SidecarPayload()
        }
        try {
            // Abstracted Stream Fetch (Consume stream factory to decouple parser from storage references)
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
                        // EXTINF Layout Match: #EXTINF:duration,Title
                        val content = line.substring(8)
                        val commaIndex = content.indexOf(',')
                        if (commaIndex != -1) {
                            val durPart = content.substring(0, commaIndex).trim()
                            currentDurationMs = durPart.toDoubleOrNull()?.let { (it * 1000).toLong() }
                            // Manifest Text Budget (Bound EXTINF titles before they enter item state)
                            // User-controlled playlist titles are clipped while preserving the current item association.
                            currentTitle = content.substring(commaIndex + 1).limitManifestText()
                        }
                    } else if (line.startsWith("#")) {
                        // Playlist-level tags are book metadata; item titles still come from EXTINF.
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
                            // Manifest Entry Budget (Stop local playlist accumulation at the parser boundary)
                            // Oversized M3U8 files keep a deterministic partial import instead of retaining every URI.
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
            Log.e("M3u8Parser", "Failed to parse M3U8: $displayName", e)
        }
        return M3u8Result(
            // Parsed playlist metadata is sparse by design; ImportOrchestrator fills gaps from first audio.
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
        // Supports both "#KEY:value" and "#KEY=value" so common M3U/M3U8 authoring tools can be read.
        val content = line.removePrefix("#").trim()
        val separatorIndex = listOf(content.indexOf(':'), content.indexOf('='))
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        val key = content.take(separatorIndex).trim().uppercase()
        // Manifest Metadata Budget (Bound playlist-level text before MetadataSuggestion retains it)
        // User-controlled metadata tags can be much larger than normal audiobook fields, so values are clipped early.
        val value = content.drop(separatorIndex + 1).trim().trim('"').limitManifestText()
        if (key.isBlank() || value.isBlank()) return null
        return key to value
    }
}
