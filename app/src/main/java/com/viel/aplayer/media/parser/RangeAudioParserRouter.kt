package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata

// Unified embedded cover representation extracted from MP4-specific type for reuse across all range-read parsers (e.g., MP3, FLAC, Ogg/Opus).
internal data class EmbeddedCoverBytes(
    val bytes: ByteArray,
    val mimeType: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddedCoverBytes

        if (!bytes.contentEquals(other.bytes)) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        return result
    }
}

// Minimal parser inputs restricted to sourceId, fileSize, and the readRange suspension callback.
// Empowers parsers to orchestrate their own offset/length reads while VfsFileInterface supplies low-level byte range capabilities.
internal data class RangeAudioParserInput(
    val sourceId: String,
    val fileSize: Long,
    val readRange: suspend (offset: Long, length: Int) -> ByteArray?
)

// Callers explicitly control whether to extract embedded cover images, preventing redundant reads of large cover blocks during basic metadata queries.
internal data class RangeAudioParseOptions(
    val includeEmbeddedCover: Boolean = false
)

// All format-specific parsers return this unified result structure; fallback assignment and encoding repair are handled by MetadataResolver.
internal data class RangeAudioParseResult(
    val metadata: AudiobookMetadata,
    val embeddedCover: EmbeddedCoverBytes? = null
)

// Each parser focuses solely on its supported extensions and container layouts, containing its own range reading tactics.
internal interface RangeAudioFormatParser {
    fun supports(displayName: String): Boolean

    suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions = RangeAudioParseOptions()
    ): RangeAudioParseResult?
}

// The router dispatches to the correct parser based on file extension and does not participate in low-level format parsing details.
internal object RangeAudioParserRouter {
    private val parsers: List<RangeAudioFormatParser> = listOf(
        Mp3MetadataRangeParser,
        FlacMetadataRangeParser,
        OggOpusMetadataRangeParser,
        WavMetadataRangeParser,
        AacMetadataRangeParser
    )

    suspend fun parse(
        displayName: String,
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions = RangeAudioParseOptions()
    ): RangeAudioParseResult? =
        parsers.firstOrNull { parser -> parser.supports(displayName) }?.parse(input, options)
}
