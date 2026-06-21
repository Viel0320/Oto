package com.viel.aplayer.media.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests for Id3TagReader verifying both ID3v1 and ID3v2 metadata parsing behavior.
 */
class Id3TagReaderTest {

    @Test
    fun testReadId3v1Valid() = runBlocking {
        val title = "Test Title".padEnd(30, '\u0000')
        val author = "Test Artist".padEnd(30, '\u0000')
        val album = "Test Album".padEnd(30, '\u0000')
        val year = "2026"

        val id3v1Bytes = ByteArray(128)
        System.arraycopy("TAG".toByteArray(StandardCharsets.ISO_8859_1), 0, id3v1Bytes, 0, 3)
        System.arraycopy(title.toByteArray(StandardCharsets.ISO_8859_1), 0, id3v1Bytes, 3, 30)
        System.arraycopy(author.toByteArray(StandardCharsets.ISO_8859_1), 0, id3v1Bytes, 33, 30)
        System.arraycopy(album.toByteArray(StandardCharsets.ISO_8859_1), 0, id3v1Bytes, 63, 30)
        System.arraycopy(year.toByteArray(StandardCharsets.ISO_8859_1), 0, id3v1Bytes, 93, 4)

        val input = RangeAudioParserInput(
            sourceId = "test-v1",
            fileSize = 128L,
            readRange = { offset, length ->
                id3v1Bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            }
        )

        val tag = Id3TagReader.readId3v1(input)
        assertNotNull(tag)
        assertEquals("Test Title", tag?.title)
        assertEquals("Test Artist", tag?.author)
        assertEquals("Test Album", tag?.album)
        assertEquals("2026", tag?.year)
    }

    @Test
    fun testReadId3v1InvalidTagHeader() = runBlocking {
        val id3v1Bytes = ByteArray(128)
        System.arraycopy("BAD".toByteArray(StandardCharsets.ISO_8859_1), 0, id3v1Bytes, 0, 3)

        val input = RangeAudioParserInput(
            sourceId = "test-v1-invalid",
            fileSize = 128L,
            readRange = { offset, length ->
                id3v1Bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            }
        )

        val tag = Id3TagReader.readId3v1(input)
        assertNull(tag)
    }

    @Test
    fun testReadId3v2Valid() = runBlocking {
        val text = "Hello ID3"
        val payload = ByteArray(1 + text.length + 1)
        payload[0] = 3
        System.arraycopy(text.toByteArray(StandardCharsets.UTF_8), 0, payload, 1, text.length)
        payload[payload.size - 1] = 0

        val frameHeader = ByteArray(10)
        System.arraycopy("TIT2".toByteArray(StandardCharsets.ISO_8859_1), 0, frameHeader, 0, 4)
        val size = payload.size
        frameHeader[4] = ((size shr 24) and 0xFF).toByte()
        frameHeader[5] = ((size shr 16) and 0xFF).toByte()
        frameHeader[6] = ((size shr 8) and 0xFF).toByte()
        frameHeader[7] = (size and 0xFF).toByte()

        val tagBodySize = frameHeader.size + payload.size
        val id3v2Bytes = ByteArray(10 + tagBodySize)
        System.arraycopy("ID3".toByteArray(StandardCharsets.ISO_8859_1), 0, id3v2Bytes, 0, 3)
        id3v2Bytes[3] = 3
        id3v2Bytes[4] = 0
        id3v2Bytes[5] = 0

        id3v2Bytes[6] = ((tagBodySize shr 21) and 0x7F).toByte()
        id3v2Bytes[7] = ((tagBodySize shr 14) and 0x7F).toByte()
        id3v2Bytes[8] = ((tagBodySize shr 7) and 0x7F).toByte()
        id3v2Bytes[9] = (tagBodySize and 0x7F).toByte()

        System.arraycopy(frameHeader, 0, id3v2Bytes, 10, frameHeader.size)
        System.arraycopy(payload, 0, id3v2Bytes, 10 + frameHeader.size, payload.size)

        val input = RangeAudioParserInput(
            sourceId = "test-v2",
            fileSize = id3v2Bytes.size.toLong(),
            readRange = { offset, length ->
                id3v2Bytes.copyOfRange(offset.toInt(), (offset + length).toInt().coerceAtMost(id3v2Bytes.size))
            }
        )

        val tag = Id3TagReader.readId3v2(input, RangeAudioParseOptions(includeEmbeddedCover = false))
        assertNotNull(tag)
        assertEquals("Hello ID3", tag?.title)
    }
}
