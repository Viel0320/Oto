package com.viel.aplayer.media.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Unit tests verifying FlacPictureCodec decoding and safety checks.
 */
class FlacPictureCodecTest {

    @Test
    fun testParseBlockInvalidTooShort() {
        val bytes = ByteArray(10)
        val result = FlacPictureCodec.parseBlock(bytes)
        assertNull("Should fail on bytes array shorter than 32 bytes", result)
    }

    @Test
    fun testParseBlockValid() {
        val mime = "image/jpeg"
        val mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)
        val description = "Test Cover"
        val descBytes = description.toByteArray(StandardCharsets.UTF_8)
        val imagePayload = byteArrayOf(1, 2, 3, 4, 5)

        // Calculate size: type(4) + mimeLen(4) + mime + descLen(4) + desc + details(16) + imgLen(4) + imgPayload
        val totalSize = 4 + 4 + mimeBytes.size + 4 + descBytes.size + 16 + 4 + imagePayload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(3) // type: Front Cover
        buffer.putInt(mimeBytes.size) // mime length
        buffer.put(mimeBytes)
        buffer.putInt(descBytes.size) // description length
        buffer.put(descBytes)
        buffer.putInt(800) // width
        buffer.putInt(600) // height
        buffer.putInt(24) // depth
        buffer.putInt(0) // color count
        buffer.putInt(imagePayload.size) // picture length
        buffer.put(imagePayload)

        val bytes = buffer.array()
        val result = FlacPictureCodec.parseBlock(bytes)

        assertNotNull("Should successfully parse valid FLAC picture block", result)
        assertEquals("MIME type should be correctly extracted", mime, result?.mimeType)
        assertArrayEquals("Image bytes should be correctly extracted", imagePayload, result?.bytes)
    }

    @Test
    fun testDecodeBase64Valid() {
        val mime = "image/png"
        val mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)
        val imagePayload = byteArrayOf(9, 8, 7, 6)

        // Calculate size: type(4) + mimeLen(4) + mime + descLen(4) + details(16) + imgLen(4) + imgPayload
        val totalSize = 4 + 4 + mimeBytes.size + 4 + 16 + 4 + imagePayload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(3) // type
        buffer.putInt(mimeBytes.size) // mime length
        buffer.put(mimeBytes)
        buffer.putInt(0) // description length (empty)
        buffer.putInt(0) // width
        buffer.putInt(0) // height
        buffer.putInt(0) // depth
        buffer.putInt(0) // colors
        buffer.putInt(imagePayload.size) // picture length
        buffer.put(imagePayload)

        val base64Str = Base64.getEncoder().encodeToString(buffer.array())
        val result = FlacPictureCodec.decodeBase64(base64Str)

        assertNotNull("Should successfully decode base64 picture block", result)
        assertEquals("MIME type should match", mime, result?.mimeType)
        assertArrayEquals("Image bytes should match", imagePayload, result?.bytes)
    }

    @Test
    fun testParseBlockCorruptedLengths() {
        val mime = "image/jpeg"
        val mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)

        // Declared mime length (100) exceeds remaining capacity of the buffer.
        val totalSize = 4 + 4 + mimeBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(3)
        buffer.putInt(100) // Declared invalid mime length
        buffer.put(mimeBytes)

        val result = FlacPictureCodec.parseBlock(buffer.array())
        assertNull("Should return null if mime length exceeds array bounds", result)
    }
}
