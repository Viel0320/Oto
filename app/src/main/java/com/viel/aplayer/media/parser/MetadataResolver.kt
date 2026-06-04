package com.viel.aplayer.media.parser

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.media.AudiobookMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

// Import flow needs to pass both "metadata + embedded cover" down the pipeline.
// Abstracted the cover representation to generic EmbeddedCoverBytes to decouple from MP4-specific implementations.
internal data class ExtractedAudiobookMetadata(
    val metadata: AudiobookMetadata,
    val embeddedCover: EmbeddedCoverBytes?
)

/**
 * Metadata Resolver (Responsible for extracting title, author, narrator, synopsis, year, duration, and chapters from audio files)
 *
 * Beginning with this refactor, non-MP4 formats no longer traverse MediaMetadataRetriever.
 * Instead, they are delegated to format-specific range parsers, while MetadataResolver manages routing, title fallbacks, and mojibake correction.
 */
@UnstableApi
class MetadataResolver(
    // Inject the active VFS singleton or scan-snapshot VFS from the caller.
    // Eliminates implicit dependencies by avoiding internal construction of AppDatabase -> libraryRootDao -> VfsFileInterface.
    private val fileReader: VfsFileInterface
) {
    // Global metadata extraction concurrency remains capped at 4.
    // Prevents memory and I/O thrashing caused by multiple parsers reading headers/trailers of large files during batch imports.
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
            // The parser only yields raw container semantics; fallback title assignment and mojibake repair remain localized in MetadataResolver.
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
        // Fall back strictly to the filename if the parser fails completely, avoiding any secondary full-file probes.
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
