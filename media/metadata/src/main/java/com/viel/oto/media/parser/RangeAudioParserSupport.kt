package com.viel.oto.media.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Shared byte-reading and metadata formatting helpers used by range-read format parsers.
 */
internal object RangeAudioParserSupport {
    fun ByteArray.readUInt16BE(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    fun ByteArray.readUInt16LE(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    fun ByteArray.readUInt24BE(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 16) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            (this[offset + 2].toInt() and 0xff)

    fun ByteArray.readUInt24LE(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)

    fun ByteArray.readUInt32BE(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)

    fun ByteArray.readUInt32LE(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    fun ByteArray.readUInt64LE(offset: Int): Long {
        var result = 0L
        for (index in 0 until 8) {
            result = result or ((this[offset + index].toLong() and 0xff) shl (index * 8))
        }
        return result
    }

    fun ByteArray.readSyncSafeInt(offset: Int): Int =
        ((this[offset].toInt() and 0x7f) shl 21) or
            ((this[offset + 1].toInt() and 0x7f) shl 14) or
            ((this[offset + 2].toInt() and 0x7f) shl 7) or
            (this[offset + 3].toInt() and 0x7f)

    fun readNullTerminatedText(
        bytes: ByteArray,
        start: Int,
        charset: Charset
    ): Pair<String, Int> {
        if (start >= bytes.size) return "" to start
        if (charset == StandardCharsets.UTF_16BE || charset == StandardCharsets.UTF_16LE || charset.name().startsWith("UTF-16")) {
            var end = start
            while (end + 1 < bytes.size) {
                if (bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) break
                end += 2
            }
            val text = bytes.copyOfRange(start, end).toString(charset).trim('\u0000')
            val next = if (end + 1 < bytes.size) end + 2 else bytes.size
            return text to next
        }

        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) {
            end++
        }
        val text = bytes.copyOfRange(start, end).toString(charset).trim('\u0000')
        val next = if (end < bytes.size) end + 1 else bytes.size
        return text to next
    }

    fun normalizeTrackIndex(value: String?): Int? =
        value
            ?.substringBefore('/')
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    fun normalizeYear(value: String?): String =
        Regex("\\d{4}").find(value.orEmpty())?.value ?: value.orEmpty().trim()

    fun mergeFirstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    fun cString(bytes: ByteArray, start: Int, length: Int): String =
        bytes.copyOfRange(start, start + length)
            .toString(StandardCharsets.ISO_8859_1)
            .trim('\u0000', ' ', '\n', '\r', '\t')

    fun littleEndianBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Constructs an EmbeddedCoverBytes container wrapper around the raw image bytes.
     * Returns null if the byte payload is empty.
     */
    fun embeddedCover(bytes: ByteArray, mimeType: String?): EmbeddedCoverBytes? =
        bytes.takeIf { it.isNotEmpty() }?.let { EmbeddedCoverBytes(bytes = it, mimeType = mimeType) }

    /**
     * Formats a standardized 1-based sequential chapter title for display fallback.
     */
    fun chapterTitle(index: Int): String =
        "Chapter ${index + 1}"
}
