package com.viel.aplayer.media.parser

import com.viel.aplayer.data.runCatchingCancellable
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Shared codec helper for decoding and parsing FLAC metadata picture block structures.
 * This is used to extract embedded cover images from FLAC files and Vorbis comments (Ogg/Opus).
 */
internal object FlacPictureCodec {

    /**
     * Decodes a Base64 encoded FLAC picture block.
     * Returns null if decoding or parsing fails.
     */
    fun decodeBase64(value: String): EmbeddedCoverBytes? =
        runCatchingCancellable {
            parseBlock(Base64.getDecoder().decode(value))
        }.getOrNull()

    /**
     * Parses a raw binary FLAC picture block according to the FLAC Metadata Block Picture specification.
     * Maps the extracted picture bytes to an EmbeddedCoverBytes wrapper.
     */
    fun parseBlock(bytes: ByteArray): EmbeddedCoverBytes? {
        if (bytes.size < 32) return null
        var cursor = 0

        RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        val mimeLengthLong = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        if (mimeLengthLong !in 0L..(bytes.size - cursor).toLong()) return null
        val mimeLength = mimeLengthLong.toInt()
        val mimeType = bytes.copyOfRange(cursor, cursor + mimeLength).toString(StandardCharsets.UTF_8).ifBlank { null }
        cursor += mimeLength

        val descriptionLengthLong = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        if (descriptionLengthLong !in 0L..(bytes.size - cursor).toLong()) return null
        val descriptionLength = descriptionLengthLong.toInt()
        cursor += descriptionLength

        if (cursor + 20 > bytes.size) return null
        cursor += 16

        val pictureLengthLong = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        if (pictureLengthLong <= 0L || pictureLengthLong > (bytes.size - cursor).toLong()) return null
        val pictureLength = pictureLengthLong.toInt()

        val imageBytes = bytes.copyOfRange(cursor, cursor + pictureLength)

        return RangeAudioParserSupport.embeddedCover(imageBytes, mimeType)
    }
}
