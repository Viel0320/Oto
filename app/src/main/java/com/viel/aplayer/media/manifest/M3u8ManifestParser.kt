package com.viel.aplayer.media.manifest

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import com.viel.aplayer.library.MetadataSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun parse(displayName: String, openStream: suspend () -> InputStream?): M3u8Result {
        val items = mutableListOf<M3u8Item>()
        var playlistTitle: String? = null
        var playlistAuthor: String? = null
        var playlistNarrator: String? = null
        var playlistYear: String? = null
        var playlistDescription: String? = null
        try {
            // 为每一次改动添加详尽的中文注释：M3U8 解析器只依赖 VFS 流工厂，避免清单解析层重新接触来源原生文件对象。
            val inputStream = openStream() ?: return M3u8Result(MetadataSuggestion(), emptyList())
            val reader = BufferedReader(withContext(Dispatchers.IO) {
                InputStreamReader(inputStream, "UTF-8")
            })

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
