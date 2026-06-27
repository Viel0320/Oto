package com.viel.oto.media.parser

import com.viel.oto.media.parser.RangeAudioParserSupport.readSyncSafeInt
import com.viel.oto.media.parser.RangeAudioParserSupport.readUInt16BE
import com.viel.oto.media.parser.RangeAudioParserSupport.readUInt16LE
import com.viel.oto.media.parser.RangeAudioParserSupport.readUInt24BE
import com.viel.oto.media.parser.RangeAudioParserSupport.readUInt24LE
import com.viel.oto.media.parser.RangeAudioParserSupport.readUInt32BE
import com.viel.oto.media.parser.RangeAudioParserSupport.readUInt32LE
import com.viel.oto.media.parser.RangeAudioParserSupport.readUInt64LE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Locks the shared byte-reading and metadata-normalization helpers reused by every range-read format
 * parser. A regression here silently corrupts duration, track, and text extraction across all formats,
 * so the boundary cases (unsigned high bit, syncsafe stripping, UTF-16 termination) are pinned here.
 */
class RangeAudioParserSupportTest {

    @Test
    fun `readUInt16BE and LE read byte order and the unsigned high bit`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0x01.toByte())

        assertEquals(0xFF01, bytes.readUInt16BE(0))
        assertEquals(0x01FF, bytes.readUInt16LE(0))
    }

    @Test
    fun `readUInt16 reads at an offset`() {
        val bytes = byteArrayOf(0x00, 0x12, 0x34)

        assertEquals(0x1234, bytes.readUInt16BE(1))
    }

    @Test
    fun `readUInt24BE and LE cover the full three-byte unsigned range`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte())

        assertEquals(0xFFEEDD, bytes.readUInt24BE(0))
        assertEquals(0xDDEEFF, bytes.readUInt24LE(0))
    }

    @Test
    fun `readUInt32BE and LE keep the high bit unsigned in a Long`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x01)

        assertEquals(0xFF000001L, bytes.readUInt32BE(0))
        assertEquals(0x010000FFL, bytes.readUInt32LE(0))
    }

    @Test
    fun `readUInt32BE reads the maximum unsigned value without sign extension`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertEquals(0xFFFFFFFFL, bytes.readUInt32BE(0))
    }

    @Test
    fun `readUInt64LE assembles eight little-endian bytes`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

        assertEquals(0x0807060504030201L, bytes.readUInt64LE(0))
    }

    @Test
    fun `readSyncSafeInt strips the high bit of each byte`() {
        // ID3 syncsafe: each byte carries only 7 significant bits. 0x7F7F7F7F decodes to 28 set bits.
        val bytes = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F)

        assertEquals(0x0FFFFFFF, bytes.readSyncSafeInt(0))
    }

    @Test
    fun `readSyncSafeInt ignores the unused top bit of each byte`() {
        // Top bit set on every byte must be discarded, leaving zero.
        val bytes = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())

        assertEquals(0, bytes.readSyncSafeInt(0))
    }

    @Test
    fun `readSyncSafeInt decodes a known multi-byte size`() {
        // 0x00 0x00 0x02 0x01 -> (2 << 7) | 1 = 257
        val bytes = byteArrayOf(0x00, 0x00, 0x02, 0x01)

        assertEquals(257, bytes.readSyncSafeInt(0))
    }

    @Test
    fun `normalizeTrackIndex parses a bare number`() {
        assertEquals(3, RangeAudioParserSupport.normalizeTrackIndex("3"))
    }

    @Test
    fun `normalizeTrackIndex takes the part before a slash`() {
        assertEquals(3, RangeAudioParserSupport.normalizeTrackIndex("3/12"))
    }

    @Test
    fun `normalizeTrackIndex trims surrounding whitespace`() {
        assertEquals(7, RangeAudioParserSupport.normalizeTrackIndex("  7 / 20 "))
    }

    @Test
    fun `normalizeTrackIndex rejects zero and negatives`() {
        assertNull(RangeAudioParserSupport.normalizeTrackIndex("0"))
        assertNull(RangeAudioParserSupport.normalizeTrackIndex("-1"))
    }

    @Test
    fun `normalizeTrackIndex rejects non-numeric and null input`() {
        assertNull(RangeAudioParserSupport.normalizeTrackIndex("abc"))
        assertNull(RangeAudioParserSupport.normalizeTrackIndex(null))
        assertNull(RangeAudioParserSupport.normalizeTrackIndex(""))
    }

    @Test
    fun `normalizeYear extracts a four-digit year from a longer string`() {
        assertEquals("2020", RangeAudioParserSupport.normalizeYear("2020-01-15"))
    }

    @Test
    fun `normalizeYear returns the first four-digit run`() {
        assertEquals("1999", RangeAudioParserSupport.normalizeYear("Released 1999 remastered 2015"))
    }

    @Test
    fun `normalizeYear falls back to the trimmed original when no four-digit run exists`() {
        assertEquals("99", RangeAudioParserSupport.normalizeYear("  99  "))
    }

    @Test
    fun `normalizeYear returns empty for null`() {
        assertEquals("", RangeAudioParserSupport.normalizeYear(null))
    }

    @Test
    fun `mergeFirstNonBlank returns the first non-blank trimmed value`() {
        assertEquals("real", RangeAudioParserSupport.mergeFirstNonBlank(null, "", "  ", "  real  ", "later"))
    }

    @Test
    fun `mergeFirstNonBlank returns empty when all values are blank`() {
        assertEquals("", RangeAudioParserSupport.mergeFirstNonBlank(null, "", "   "))
    }

    @Test
    fun `readNullTerminatedText reads a UTF-8 string up to the null terminator`() {
        val bytes = "hello".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) + "tail".toByteArray(StandardCharsets.UTF_8)

        val (text, next) = RangeAudioParserSupport.readNullTerminatedText(bytes, 0, StandardCharsets.UTF_8)

        assertEquals("hello", text)
        // Next index points just past the single null terminator, at the start of "tail".
        assertEquals(6, next)
    }

    @Test
    fun `readNullTerminatedText without a terminator consumes to the end`() {
        val bytes = "endless".toByteArray(StandardCharsets.UTF_8)

        val (text, next) = RangeAudioParserSupport.readNullTerminatedText(bytes, 0, StandardCharsets.UTF_8)

        assertEquals("endless", text)
        assertEquals(bytes.size, next)
    }

    @Test
    fun `readNullTerminatedText at or past the end returns empty`() {
        val bytes = byteArrayOf(1, 2, 3)

        val (text, next) = RangeAudioParserSupport.readNullTerminatedText(bytes, 3, StandardCharsets.UTF_8)

        assertEquals("", text)
        assertEquals(3, next)
    }

    @Test
    fun `readNullTerminatedText reads UTF-16BE up to the double-null terminator`() {
        val bytes = "hi".toByteArray(StandardCharsets.UTF_16BE) + byteArrayOf(0, 0) + "x".toByteArray(StandardCharsets.UTF_16BE)

        val (text, next) = RangeAudioParserSupport.readNullTerminatedText(bytes, 0, StandardCharsets.UTF_16BE)

        assertEquals("hi", text)
        // "hi" is 4 bytes, terminator at index 4..5, next points past it.
        assertEquals(6, next)
    }

    @Test
    fun `cString trims null padding and whitespace`() {
        val bytes = "tag".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0, 0, 0)

        assertEquals("tag", RangeAudioParserSupport.cString(bytes, 0, 6))
    }
}
