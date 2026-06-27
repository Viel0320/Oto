package com.viel.oto.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch/boundary coverage for [PlaybackMediaId].
 *
 * Real API under test:
 *   - compose(bookId, fileId): String  -> "oto-mid:v1:<bookId.length>:<bookId><fileId>:<checksum>"
 *   - parse(mediaId: String?): Parts?
 *
 * Expected parse boundaries follow the PREFIX "oto-mid:v1:" and the final checksum separator.
 */
class PlaybackMediaIdTest {

    // ---- compose format ------------------------------------------------------

    @Test
    fun `compose emits length-prefixed v1 schema with checksum`() {
        val mediaId = PlaybackMediaId.compose("book-1", "file-1")

        assertTrue(Regex("oto-mid:v1:6:book-1file-1:[0-9a-f]{16}").matches(mediaId))
    }

    @Test
    fun `compose with colon-bearing ids records book length`() {
        val mediaId = PlaybackMediaId.compose("a:b", "c:d:e")

        assertTrue(Regex("oto-mid:v1:3:a:bc:d:e:[0-9a-f]{16}").matches(mediaId))
    }

    @Test
    fun `compose with empty book id uses zero length`() {
        val mediaId = PlaybackMediaId.compose("", "file")

        assertTrue(Regex("oto-mid:v1:0:file:[0-9a-f]{16}").matches(mediaId))
    }

    // ---- successful round-trips ---------------------------------------------

    @Test
    fun `round trips simple ids`() {
        val mediaId = PlaybackMediaId.compose("book-1", "file-1")
        val parts = PlaybackMediaId.parse(mediaId)
        assertEquals(PlaybackMediaId.Parts(bookId = "book-1", fileId = "file-1"), parts)
    }

    @Test
    fun `round trips ids that themselves contain colons`() {
        // The length prefix makes colon-bearing bookId/fileId reversible.
        val mediaId = PlaybackMediaId.compose("a:b", "c:d:e")
        val parts = PlaybackMediaId.parse(mediaId)
        assertEquals(PlaybackMediaId.Parts(bookId = "a:b", fileId = "c:d:e"), parts)
    }

    @Test
    fun `round trips bookId that looks like the prefix structure`() {
        val mediaId = PlaybackMediaId.compose("oto-mid:v1:9", "tail")
        val parts = PlaybackMediaId.parse(mediaId)
        assertEquals(PlaybackMediaId.Parts(bookId = "oto-mid:v1:9", fileId = "tail"), parts)
    }

    // ---- null / blank inputs -------------------------------------------------

    @Test
    fun `parse null returns null`() {
        assertNull(PlaybackMediaId.parse(null))
    }

    @Test
    fun `parse empty returns null`() {
        assertNull(PlaybackMediaId.parse(""))
    }

    @Test
    fun `parse blank returns null`() {
        assertNull(PlaybackMediaId.parse("   "))
    }

    // ---- prefix gate ---------------------------------------------------------

    @Test
    fun `parse without prefix returns null`() {
        assertNull(PlaybackMediaId.parse("abc"))
        assertNull(PlaybackMediaId.parse(":"))
        assertNull(PlaybackMediaId.parse("oto-mid:v2:6:bookfile"))
    }

    // ---- length-separator missing (indexOf == -1) ---------------------------

    @Test
    fun `parse with no separator after prefix returns null`() {
        // from index 11 onward there is no ':' -> lengthSeparatorIndex == -1
        assertNull(PlaybackMediaId.parse("oto-mid:v1:3abc"))
    }

    @Test
    fun `parse without checksum returns null`() {
        assertNull(PlaybackMediaId.parse("oto-mid:v1:6:book-1file-1"))
    }

    // ---- length segment is not an integer (toIntOrNull) ---------------------

    @Test
    fun `parse with non-numeric length returns null`() {
        assertNull(PlaybackMediaId.parse("oto-mid:v1:x:bookfile"))
    }

    // ---- length <= 0 ---------------------------------------------------------

    @Test
    fun `parse with zero length returns null`() {
        val mediaId = PlaybackMediaId.compose("", "file")

        assertNull(PlaybackMediaId.parse(mediaId))
    }

    @Test
    fun `parse with negative length returns null`() {
        // "-1" parses as -1 -> bookIdLength <= 0 branch
        assertNull(PlaybackMediaId.parse("oto-mid:v1:-1:abcd:0123456789abcdef"))
    }

    // ---- bookIdEnd > length --------------------------------------------------

    @Test
    fun `parse with length exceeding remaining text returns null`() {
        // bookIdLength = 9 but only 2 payload chars remain before the checksum separator.
        assertNull(PlaybackMediaId.parse("oto-mid:v1:9:ab:0123456789abcdef"))
    }

    // ---- bookId blank --------------------------------------------------------

    @Test
    fun `parse with whitespace-only book id returns null`() {
        val mediaId = PlaybackMediaId.compose("   ", "file")

        assertNull(PlaybackMediaId.parse(mediaId))
    }

    // ---- fileId blank --------------------------------------------------------

    @Test
    fun `parse with empty file id returns null`() {
        val mediaId = PlaybackMediaId.compose("book", "")

        assertNull(PlaybackMediaId.parse(mediaId))
    }

    @Test
    fun `compose with empty file id does not round trip`() {
        // Documents that an empty fileId yields a non-parseable id.
        val mediaId = PlaybackMediaId.compose("book", "")
        assertNull(PlaybackMediaId.parse(mediaId))
    }

    @Test
    fun `compose with empty book id does not round trip`() {
        val mediaId = PlaybackMediaId.compose("", "file")
        assertNull(PlaybackMediaId.parse(mediaId))
    }

    // ---- checksum integrity --------------------------------------------------

    @Test
    fun `parse rejects a changed payload with the original checksum`() {
        val mediaId = PlaybackMediaId.compose("book-1", "file-1")
        val tampered = mediaId.replace("file-1", "file-2")

        assertNull(PlaybackMediaId.parse(tampered))
    }

    @Test
    fun `parse rejects a retargeted book length boundary`() {
        val mediaId = PlaybackMediaId.compose("book", "file")
        val tampered = mediaId.replace("oto-mid:v1:4:", "oto-mid:v1:3:")

        assertNull(PlaybackMediaId.parse(tampered))
    }

    @Test
    fun `parse rejects a changed checksum`() {
        val mediaId = PlaybackMediaId.compose("book-1", "file-1")
        val tampered = mediaId.dropLast(1) + if (mediaId.last() == '0') "1" else "0"

        assertNull(PlaybackMediaId.parse(tampered))
    }
}
