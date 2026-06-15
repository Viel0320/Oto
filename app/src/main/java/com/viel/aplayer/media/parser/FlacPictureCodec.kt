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
            // Decodes the base64 string to raw bytes and forwards it to the binary parser.
            parseBlock(Base64.getDecoder().decode(value))
        }.getOrNull()

    /**
     * Parses a raw binary FLAC picture block according to the FLAC Metadata Block Picture specification.
     * Maps the extracted picture bytes to an EmbeddedCoverBytes wrapper.
     */
    fun parseBlock(bytes: ByteArray): EmbeddedCoverBytes? {
        // Safe check: FLAC picture block layout requires at least 32 bytes for minimum header components.
        if (bytes.size < 32) return null
        var cursor = 0

        // Skip the 4-byte picture type field.
        RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        // Parse 4-byte MIME type string length.
        val mimeLengthLong = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        // Bounds safety check: ensure the declared MIME length fits within the remaining buffer capacity.
        if (mimeLengthLong !in 0L..(bytes.size - cursor).toLong()) return null
        val mimeLength = mimeLengthLong.toInt()
        val mimeType = bytes.copyOfRange(cursor, cursor + mimeLength).toString(StandardCharsets.UTF_8).ifBlank { null }
        cursor += mimeLength

        // Parse 4-byte Description string length.
        val descriptionLengthLong = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        // Bounds safety check: ensure the description length fits within the remaining buffer capacity.
        if (descriptionLengthLong !in 0L..(bytes.size - cursor).toLong()) return null
        val descriptionLength = descriptionLengthLong.toInt()
        cursor += descriptionLength

        // Layout validation: ensure there is enough remaining space to read details (width, height, depth, colors) and picture payload length.
        if (cursor + 20 > bytes.size) return null
        // Skip width (4 bytes), height (4 bytes), depth (4 bytes), and number of colors (4 bytes) = 16 bytes.
        cursor += 16

        // Parse 4-byte picture payload binary length.
        val pictureLengthLong = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }
        cursor += 4

        // Bounds safety check: ensure the image payload length is positive and fits comfortably within the remaining buffer limits.
        if (pictureLengthLong <= 0L || pictureLengthLong > (bytes.size - cursor).toLong()) return null
        val pictureLength = pictureLengthLong.toInt()

        // Extract raw image payload bytes.
        val imageBytes = bytes.copyOfRange(cursor, cursor + pictureLength)

        // Delegate to unified helper to wrap payload bytes into EmbeddedCoverBytes.
        return RangeAudioParserSupport.embeddedCover(imageBytes, mimeType)
    }
}
