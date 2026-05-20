package com.viel.aplayer.library.manifest

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import com.viel.aplayer.library.MetadataSuggestion

/**
 * M3U8/M3U 播放列表解析器。
 */
object M3u8ManifestParser {

    data class M3u8Item(
        val uri: String,
        val title: String?,
        val durationMs: Long?
    )

    data class M3u8Result(
        val metadata: MetadataSuggestion,
        val items: List<M3u8Item>
    )

    fun parse(context: Context, m3uFile: DocumentFile): M3u8Result {
        val items = mutableListOf<M3u8Item>()
        var playlistTitle: String? = null
        var playlistAuthor: String? = null
        var playlistNarrator: String? = null
        var playlistYear: String? = null
        var playlistDescription: String? = null
        try {
            val inputStream = context.contentResolver.openInputStream(m3uFile.uri)
                ?: return M3u8Result(MetadataSuggestion(), emptyList())
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

            var currentTitle: String? = null
            var currentDurationMs: Long? = null

            reader.useLines { lines ->
                lines.forEach { rawLine ->
                    var line = rawLine.trim()
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1).trim()
                    }
                    if (line.isBlank()) return@forEach

                    if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                        // 解析格式: #EXTINF:duration,Title
                        val content = line.substring(8)
                        val commaIndex = content.indexOf(',')
                        if (commaIndex != -1) {
                            val durPart = content.substring(0, commaIndex).trim()
                            currentDurationMs = durPart.toDoubleOrNull()?.let { (it * 1000).toLong() }
                            currentTitle = content.substring(commaIndex + 1).trim()
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
                            items.add(M3u8Item(uri = line, title = currentTitle, durationMs = currentDurationMs))
                        }
                        currentTitle = null
                        currentDurationMs = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("M3u8Parser", "Failed to parse M3U8: ${m3uFile.name}", e)
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
            items = items
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
        val value = content.drop(separatorIndex + 1).trim().trim('"')
        if (key.isBlank() || value.isBlank()) return null
        return key to value
    }
}