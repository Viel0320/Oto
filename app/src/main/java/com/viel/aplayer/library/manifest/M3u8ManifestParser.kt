package com.viel.aplayer.library.manifest

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * M3U8/M3U 播放列表解析器。
 */
object M3u8ManifestParser {

    data class M3u8Item(
        val uri: String,
        val title: String?,
        val durationMs: Long?
    )

    fun parse(context: Context, m3uFile: DocumentFile): List<M3u8Item> {
        val items = mutableListOf<M3u8Item>()
        try {
            val inputStream = context.contentResolver.openInputStream(m3uFile.uri) ?: return emptyList()
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
        return items
    }
}
