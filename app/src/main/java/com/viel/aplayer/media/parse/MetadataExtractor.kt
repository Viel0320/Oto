package com.viel.aplayer.media.parse

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.vfs.VfsFileReader
import com.viel.aplayer.media.AudiobookMetadata

/**
 * 专门负责从音频文件中提取元数据（标题、作者、播讲人、描述、年份及章节）的组件。
 */
@UnstableApi
class MetadataExtractor(private val context: Context) {

    // 为每一次改动添加详尽的中文注释：引入全局 Semaphore(4) 严格限制元数据物理提取的最大并发度。
    // 在全盘/增量重扫期间，多协程并发极易导致多个几十小时长的 M4B 大文件在底层 MediaMetadataRetriever 
    // 及 Media3 内部被并发读取并展开巨大的 stbl (Sample Table) 采样表，从而引起 JVM 堆内存剧烈抖动与 OutOfMemoryError 崩溃。
    // 通过本限制，任何时刻全局最多只有 4 个音频文件同时提取元数据，彻底根除 OOM 和 SAF 跨进程死锁风险。
    private val semaphore = kotlinx.coroutines.sync.Semaphore(4)
    private val fileReader = VfsFileReader(context.applicationContext, com.viel.aplayer.data.db.AppDatabase.getInstance(context.applicationContext).libraryRootDao())

    suspend fun extract(file: FileRef): AudiobookMetadata = semaphore.withPermit {
        // 为每一次改动添加详尽的中文注释：扫描期元数据提取以 FileRef 的 rootId/sourcePath 打开 VFS FD，不再依赖 provider URI。
        withContext(Dispatchers.IO) {
            fileReader.openFileDescriptor(file)?.use { pfd ->
                extractFromFileDescriptor(file.displayName, file.vfsKey, pfd)
            } ?: AudiobookMetadata(
                title = file.displayName.substringBeforeLast('.'),
                author = "",
                narrator = "",
                description = "",
                year = "",
                durationMs = 0L
            )
        }
    }

    suspend fun extract(file: BookFileEntity): AudiobookMetadata = semaphore.withPermit {
        // 为每一次改动添加详尽的中文注释：入库后强制重建元数据同样通过 BookFileEntity 的 VFS 路径读取。
        withContext(Dispatchers.IO) {
            fileReader.openFileDescriptor(file)?.use { pfd ->
                extractFromFileDescriptor(file.displayName, "${file.rootId}:${file.sourcePath}", pfd)
            } ?: AudiobookMetadata(
                title = file.displayName.substringBeforeLast('.'),
                author = "",
                narrator = "",
                description = "",
                year = "",
                durationMs = 0L
            )
        }
    }

    private fun extractFromFileDescriptor(
        displayName: String,
        sourceId: String,
        pfd: ParcelFileDescriptor
    ): AudiobookMetadata {
        val retriever = MediaMetadataRetriever()
        var title = ""
        var author = ""
        var narrator = ""
        var album = ""
        var trackIndex: Int? = null
        var description = ""
        var duration = 0L
        var year = ""
        var chapters = emptyList<ChapterEntity>()

        try {
            retriever.setDataSource(pfd.fileDescriptor)

            // 为每一次改动添加详尽的中文注释：VFS FD 入口没有可显示 URI，标题兜底直接使用扫描/入库时保存的 displayName。
            val rawTitle = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            title = if (rawTitle.isNotBlank() && !rawTitle.contains("/")) {
                rawTitle
            } else {
                displayName.substringBeforeLast('.')
            }

            author = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            narrator = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER))
            album = normalizeMetadataText(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            trackIndex = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore("/")
                ?.trim()
                ?.toIntOrNull()

            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!rawYear.isNullOrBlank()) {
                val normalizedYear = normalizeMetadataText(rawYear)
                year = Regex("\\d{4}").find(normalizedYear)?.value ?: normalizedYear
            }

            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            // 为每一次改动添加详尽的中文注释：FD 入口当前跳过 Media3 URI MetadataRetriever，改用低层 MP4 FD 解析保留 m4b/m4a/mp4 章节能力。
            chapters = AudiobookParser.extractChaptersLowLevel(pfd, sourceId)
                .map { chapter ->
                    chapter.copy(
                        bookId = "TEMP",
                        title = normalizeMetadataText(chapter.title).ifBlank { chapter.title.trim() }
                    )
                }
        } catch (e: Exception) {
            Log.e("MetadataExtractor", "Failed to extract metadata from VFS FD: $sourceId", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        return AudiobookMetadata(
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
