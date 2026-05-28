package com.viel.aplayer.media.parser

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.media.AudiobookMetadata
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// 导入链路需要把“元数据 + 内嵌封面”一起向后传递，
// 这里把封面类型抽象成通用的 EmbeddedCoverBytes，不再绑定到 MP4 专属实现。
internal data class ExtractedAudiobookMetadata(
    val metadata: AudiobookMetadata,
    val embeddedCover: EmbeddedCoverBytes?
)

/**
 * 负责从音频文件中提取标题、作者、旁白、简介、年份、时长与章节信息。
 *
 * 从这次重构开始，非 MP4 格式不再走 MediaMetadataRetriever，
 * 而是统一交给各自的范围读取 parser；MetadataResolver 只负责路由、标题兜底与乱码修正。
 */
@UnstableApi
class MetadataResolver(
    // 由调用方注入运行期 VFS 单例或扫描快照 VFS，
    // 消除内部自构 AppDatabase→libraryRootDao→VfsFileInterface 的隐式依赖。
    private val fileReader: VfsFileInterface
) {
    // 全局元数据提取并发仍然限制在 4，
    // 防止大批量导入时多个 parser 同时读取大文件头尾造成内存与 I/O 抖动。
    private val semaphore = kotlinx.coroutines.sync.Semaphore(4)

    suspend fun extract(file: FileRef): AudiobookMetadata =
        extractInternal(file, includeEmbeddedCover = false).metadata

    suspend fun extract(file: BookFileEntity): AudiobookMetadata =
        extractInternal(file, includeEmbeddedCover = false).metadata

    internal suspend fun extractWithEmbeddedCover(file: FileRef): ExtractedAudiobookMetadata =
        extractInternal(file, includeEmbeddedCover = true)

    internal suspend fun extractWithEmbeddedCover(file: BookFileEntity): ExtractedAudiobookMetadata =
        extractInternal(file, includeEmbeddedCover = true)

    private suspend fun extractInternal(file: FileRef, includeEmbeddedCover: Boolean): ExtractedAudiobookMetadata =
        semaphore.withPermit {
            withContext(Dispatchers.IO) {
                if (Mp4MetadataFrameReader.supports(file.displayName)) {
                    val extracted = if (includeEmbeddedCover) {
                        extractFromMp4FramesWithCover(file)
                    } else {
                        extractFromMp4Frames(file)?.let { metadata ->
                            ExtractedAudiobookMetadata(metadata, embeddedCover = null)
                        }
                    }
                    return@withContext extracted
                        ?: ExtractedAudiobookMetadata(fallbackMetadata(file.displayName), embeddedCover = null)
                }

                extractFromRangeParser(
                    displayName = file.displayName,
                    sourceId = file.vfsKey,
                    fileSize = file.fileSize,
                    includeEmbeddedCover = includeEmbeddedCover
                ) { offset, length ->
                    fileReader.readRange(file, offset, length)
                } ?: ExtractedAudiobookMetadata(fallbackMetadata(file.displayName), embeddedCover = null)
            }
        }

    private suspend fun extractInternal(file: BookFileEntity, includeEmbeddedCover: Boolean): ExtractedAudiobookMetadata =
        semaphore.withPermit {
            withContext(Dispatchers.IO) {
                if (Mp4MetadataFrameReader.supports(file.displayName)) {
                    val extracted = if (includeEmbeddedCover) {
                        extractFromMp4FramesWithCover(file)
                    } else {
                        extractFromMp4Frames(file)?.let { metadata ->
                            ExtractedAudiobookMetadata(metadata, embeddedCover = null)
                        }
                    }
                    return@withContext extracted
                        ?: ExtractedAudiobookMetadata(fallbackMetadata(file.displayName), embeddedCover = null)
                }

                extractFromRangeParser(
                    displayName = file.displayName,
                    sourceId = "${file.rootId}:${file.sourcePath}",
                    fileSize = file.fileSize,
                    includeEmbeddedCover = includeEmbeddedCover
                ) { offset, length ->
                    fileReader.readRange(file, offset, length)
                } ?: ExtractedAudiobookMetadata(fallbackMetadata(file.displayName), embeddedCover = null)
            }
        }

    private suspend fun extractFromMp4Frames(file: FileRef): AudiobookMetadata? =
        Mp4MetadataFrameReader.extractMetadata(file, fileReader)?.normalizeMetadata(file.displayName)

    private suspend fun extractFromMp4Frames(file: BookFileEntity): AudiobookMetadata? =
        Mp4MetadataFrameReader.extractMetadata(file, fileReader)?.normalizeMetadata(file.displayName)

    private suspend fun extractFromMp4FramesWithCover(file: FileRef): ExtractedAudiobookMetadata? =
        Mp4MetadataFrameReader.extractMetadataResult(file, fileReader)?.let { result ->
            ExtractedAudiobookMetadata(
                metadata = result.metadata.normalizeMetadata(file.displayName),
                embeddedCover = result.cover?.let { cover ->
                    EmbeddedCoverBytes(bytes = cover.bytes, mimeType = cover.mimeType)
                }
            )
        }

    private suspend fun extractFromMp4FramesWithCover(file: BookFileEntity): ExtractedAudiobookMetadata? =
        Mp4MetadataFrameReader.extractMetadataResult(file, fileReader)?.let { result ->
            ExtractedAudiobookMetadata(
                metadata = result.metadata.normalizeMetadata(file.displayName),
                embeddedCover = result.cover?.let { cover ->
                    EmbeddedCoverBytes(bytes = cover.bytes, mimeType = cover.mimeType)
                }
            )
        }

    private suspend fun extractFromRangeParser(
        displayName: String,
        sourceId: String,
        fileSize: Long,
        includeEmbeddedCover: Boolean,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): ExtractedAudiobookMetadata? =
        RangeAudioParserRouter.parse(
            displayName = displayName,
            input = RangeAudioParserInput(
                sourceId = sourceId,
                fileSize = fileSize,
                readRange = readRange
            ),
            options = RangeAudioParseOptions(includeEmbeddedCover = includeEmbeddedCover)
        )?.let { parsed ->
            // parser 只负责返回“原始容器语义”，统一的标题兜底和乱码修复继续收口在 MetadataResolver。
            ExtractedAudiobookMetadata(
                metadata = parsed.metadata.normalizeMetadata(displayName),
                embeddedCover = parsed.embeddedCover
            )
        }

    private fun AudiobookMetadata.normalizeMetadata(displayName: String): AudiobookMetadata =
        copy(
            title = normalizeMetadataText(title).ifBlank { displayName.substringBeforeLast('.') },
            author = normalizeMetadataText(author),
            narrator = normalizeMetadataText(narrator),
            album = normalizeMetadataText(album),
            description = normalizeMetadataText(description),
            year = normalizeMetadataText(year),
            chapters = chapters.map { chapter ->
                chapter.copy(title = normalizeMetadataText(chapter.title).ifBlank { chapter.title.trim() })
            }
        )

    private fun fallbackMetadata(displayName: String): AudiobookMetadata =
        // 如果 parser 完全读不出来，就只回退到文件名级别兜底，而不是再走任何整文件探测。
        AudiobookMetadata(
            title = displayName.substringBeforeLast('.'),
            author = "",
            narrator = "",
            album = "",
            trackIndex = null,
            description = "",
            year = "",
            durationMs = 0L
        )

    private fun normalizeMetadataText(value: String?): String {
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
            String(value.toByteArray(sourceEncoding), StandardCharsets.UTF_8).trim()
        }.getOrNull()?.takeIf { repaired -> repaired.isNotBlank() }

    private fun String.hasMojibakeMarker(): Boolean =
        any { char -> char == '\uFFFD' || char == '脗' || char == '脙' || char == '芒' || char.code in 0x0080..0x009F }

    private fun String.metadataTextScore(): Int =
        count { char -> char == '\uFFFD' } * 100 +
            count { char -> char == '脗' || char == '脙' || char == '芒' } * 20 +
            count { char -> char.code in 0x0080..0x009F } * 10

    companion object {
        private val METADATA_FALLBACK_ENCODINGS: List<Charset> = listOf(
            Charset.forName("windows-1252"),
            StandardCharsets.ISO_8859_1,
            Charset.forName("Big5"),
            Charset.forName("Shift-JIS")
        )
    }
}
