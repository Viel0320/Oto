package com.viel.aplayer.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.CommentFrame
import com.viel.aplayer.util.parser.AudiobookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 专门负责从音频文件中提取元数据（标题、作者、播讲人、描述、年份及章节）的组件。
 */
@UnstableApi
class MetadataExtractor(private val context: Context) {

    /**
     * 执行提取操作。
     * @param uri 音频文件的 URI。
     */
    suspend fun extract(uri: Uri): AudiobookMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var title = ""
        var author = ""
        var narrator = ""
        var album = ""
        var trackIndex: Int? = null
        var description = ""
        var duration = 0L
        var year = ""
        var chapters = emptyList<com.viel.aplayer.data.ChapterEntity>()

        try {
            retriever.setDataSource(context, uri)
            
            // 1. 基础信息提取
            val rawTitle = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            title = if (rawTitle.isNotBlank() && !rawTitle.contains("/")) {
                rawTitle
            } else {
                uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: ""
            }

            // Text metadata is normalized once at extraction so all import paths keep the same field priority.
            author = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            narrator = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER))
            // sameAlbum 聚合必须使用音频文件的专辑字段，避免把 description 误当成专辑名。
            album = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            // ID3 track number controls generated chapter order when every file exposes it.
            trackIndex = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore("/")
                ?.trim()
                ?.toIntOrNull()
            
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) 
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!rawYear.isNullOrBlank()) {
                // Year may come from DATE and can also pass through the same mojibake repair path.
                val normalizedYear = normalizeMetadataText(rawYear)
                year = Regex("\\d{4}").find(normalizedYear)?.value ?: normalizedYear
            }

            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            // 2. 深度元数据与章节提取 (Media3)
            try {
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(if (uri.toString().endsWith(".m4b", ignoreCase = true)) "audio/mp4" else null)
                    .build()
                
                val extractorsFactory = DefaultExtractorsFactory()
                    .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_READ_SEF_DATA)
                val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)

                val trackGroups = androidx.media3.exoplayer.MetadataRetriever.retrieveMetadata(mediaSourceFactory, mediaItem).get()
                
                val metadataEntries = mutableListOf<androidx.media3.common.Metadata.Entry>()
                for (i in 0 until trackGroups.length) {
                    val group = trackGroups[i]
                    for (j in 0 until group.length) {
                        val format = group.getFormat(j)
                        val metadata = format.metadata ?: continue
                        for (k in 0 until metadata.length()) {
                            val entry = metadata.get(k)
                            metadataEntries.add(entry)
                            if (entry is CommentFrame && description.isEmpty()) {
                                // CommentFrame text is metadata too, so keep its charset handling consistent.
                                description = normalizeMetadataText(entry.text)
                            }
                        }
                    }
                }
                
                // 章节解析
                val tempBookId = "TEMP"
                val extractedChapters = AudiobookParser.extractChaptersFromMetadata(metadataEntries, tempBookId)
                chapters = extractedChapters.ifEmpty {
                    AudiobookParser.extractChaptersLowLevel(context, uri)
                        .map { it.copy(bookId = tempBookId) }
                }.map { chapter ->
                    // Embedded chapter names go through the same repair path as book-level metadata titles.
                    chapter.copy(title = normalizeMetadataText(chapter.title).ifBlank { chapter.title.trim() })
                }

            } catch (e: Exception) {
                Log.e("MetadataExtractor", "Media3 extraction failed for $uri", e)
            }

        } catch (e: Exception) {
            Log.e("MetadataExtractor", "Failed to set data source for $uri", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        AudiobookMetadata(
            title = title,
            author = author,
            narrator = narrator,
            album = album,
            trackIndex = trackIndex,
            description = description,
            year = year,
            durationMs = duration,
            chapters = chapters
        )
    }

    private fun normalizeMetadataText(value: String?): String {
        // Priority order: accept valid UTF-8-looking text first, otherwise try common wrong decoders.
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        if (!trimmed.hasMojibakeMarker()) return trimmed

        return METADATA_FALLBACK_ENCODINGS
            .mapNotNull { sourceEncoding -> decodeAsUtf8From(sourceEncoding, trimmed) }
            .minByOrNull { candidate -> candidate.metadataTextScore() }
            ?.takeIf { repaired -> repaired.metadataTextScore() < trimmed.metadataTextScore() }
            ?: trimmed
    }

    private fun decodeAsUtf8From(sourceEncoding: Charset, value: String): String? =
        runCatching {
            // Rebuild bytes as if Android decoded UTF-8 metadata with sourceEncoding, then decode as UTF-8.
            String(value.toByteArray(sourceEncoding), StandardCharsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun String.hasMojibakeMarker(): Boolean =
        // These markers cover common UTF-8-as-Windows-1252/Latin-1 artifacts such as "â€”" and replacement chars.
        any { it == '\uFFFD' || it == 'Â' || it == 'Ã' || it == 'â' || it.code in 0x0080..0x009F }

    private fun String.metadataTextScore(): Int =
        // Lower score is better: replacement chars and common mojibake starters are stronger evidence than plain text.
        count { it == '\uFFFD' } * 100 +
            count { it == 'Â' || it == 'Ã' || it == 'â' } * 20 +
            count { it.code in 0x0080..0x009F } * 10

    companion object {
        // Fallback order after the system string: UTF-8 artifacts, Big5, then Shift-JIS.
        private val METADATA_FALLBACK_ENCODINGS: List<Charset> = listOf(
            Charset.forName("windows-1252"),
            StandardCharsets.ISO_8859_1,
            Charset.forName("Big5"),
            Charset.forName("Shift-JIS")
        )
    }
}
