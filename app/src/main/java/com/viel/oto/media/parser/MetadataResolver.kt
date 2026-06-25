package com.viel.oto.media.parser

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.library.FileRef
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.media.AudiobookMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Metadata payload returned by parser backends before the import pipeline decides how to persist covers.
 */
internal data class ExtractedAudiobookMetadata(
    val metadata: AudiobookMetadata,
    val embeddedCover: EmbeddedCoverBytes?
)

/**
 * Responsible for extracting title, author, narrator, synopsis, year, duration, and chapters from audio files.
 *
 * Beginning with this refactor, non-MP4 formats no longer traverse MediaMetadataRetriever.
 * Instead, they are delegated to format-specific range parsers, while MetadataResolver manages routing, title fallbacks, and mojibake correction.
 */
@OptIn(UnstableApi::class)
class MetadataResolver(
    /**
     * The caller-owned VFS reader keeps parser execution bound to the active source snapshot instead of constructing database-backed file access internally.
     */
    private val fileReader: VfsFileInterface
) {
    /**
     * Limits concurrent metadata extraction so range parsers do not thrash memory or source I/O during batch imports.
     */
    private val semaphore = kotlinx.coroutines.sync.Semaphore(4)

    suspend fun extract(file: FileRef): AudiobookMetadata =
        extractInternal(file, includeEmbeddedCover = false).metadata

    suspend fun extract(file: BookFileEntity): AudiobookMetadata =
        extractInternal(file, includeEmbeddedCover = false).metadata

    /**
     * Extracts metadata plus a transient embedded cover payload for import-time cover persistence.
     *
     * Callers that aggregate scan results should persist or hand off embeddedCover promptly, then clear it from
     * long-lived metadata references so large image byte arrays do not survive beyond the cover-binding boundary.
     */
    internal suspend fun extractWithEmbeddedCover(file: FileRef): ExtractedAudiobookMetadata =
        extractInternal(file, includeEmbeddedCover = true)

    /**
     * Extracts metadata plus a transient embedded cover payload for database-backed recovery paths.
     *
     * The returned byte array is caller-owned and should not be cached inside broad scan summaries after the
     * cover image has been written or rejected.
     */
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
        any { char -> char == '\uFFFD' || char in MOJIBAKE_MARKERS || char.code in 0x0080..0x009F }

    private fun String.metadataTextScore(): Int =
        count { char -> char == '\uFFFD' } * 100 +
            count { char -> char in MOJIBAKE_MARKERS } * 20 +
            count { char -> char.code in 0x0080..0x009F } * 10

    companion object {
        /**
         * Keeps both legacy mojibake glyphs and escaped marker variants so metadata repair stays
         * compatible with titles observed before the comment cleanup normalized source literals.
         */
        private val MOJIBAKE_MARKERS: Set<Char> = setOf(
            '\u00C3',
            '\u00C2',
            '\u00E4',
            '\u00E5',
            '\u00E6',
            '\u8117',
            '\u8119',
            '\u8292',
            '\u9474',
            '\u8133',
            '\u9225'
        )

        private val METADATA_FALLBACK_ENCODINGS: List<Charset> = listOf(
            Charset.forName("windows-1252"),
            StandardCharsets.ISO_8859_1,
            Charset.forName("Big5"),
            Charset.forName("Shift-JIS")
        )
    }
}
