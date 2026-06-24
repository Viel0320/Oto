package com.viel.oto.media.parser

import com.viel.oto.media.AudiobookMetadata

/**
 * Unified embedded cover payload shared by all range-read audio parsers.
 */
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

/**
 * Minimal parser input that lets format parsers orchestrate their own byte-range reads.
 */
internal data class RangeAudioParserInput(
    val sourceId: String,
    val fileSize: Long,
    val readRange: suspend (offset: Long, length: Int) -> ByteArray?
)

/**
 * Parser options that let callers skip large embedded-cover reads during metadata-only queries.
 */
internal data class RangeAudioParseOptions(
    val includeEmbeddedCover: Boolean = false
)

/**
 * Unified parser result; MetadataResolver owns fallback assignment and encoding repair.
 */
internal data class RangeAudioParseResult(
    val metadata: AudiobookMetadata,
    val embeddedCover: EmbeddedCoverBytes? = null
)

/**
 * Format parser contract for extension support and container-specific range reading.
 */
internal interface RangeAudioFormatParser {
    fun supports(displayName: String): Boolean

    suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions = RangeAudioParseOptions()
    ): RangeAudioParseResult?
}

/**
 * Dispatches range metadata parsing to the first parser that supports the file name.
 */
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
