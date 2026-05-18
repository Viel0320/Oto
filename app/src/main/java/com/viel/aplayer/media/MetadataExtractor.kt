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
        var description = ""
        var duration = 0L
        var year = ""
        var chapters = emptyList<com.viel.aplayer.data.ChapterEntity>()

        try {
            retriever.setDataSource(context, uri)
            
            // 1. 基础信息提取
            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            title = if (!rawTitle.isNullOrBlank() && !rawTitle.contains("/")) {
                rawTitle
            } else {
                uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: ""
            }

            author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim() ?: ""
            narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.trim() ?: ""
            
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) 
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!rawYear.isNullOrBlank()) {
                year = Regex("\\d{4}").find(rawYear)?.value ?: rawYear
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
                                description = entry.text
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
            description = description,
            year = year,
            durationMs = duration,
            chapters = chapters
        )
    }
}
