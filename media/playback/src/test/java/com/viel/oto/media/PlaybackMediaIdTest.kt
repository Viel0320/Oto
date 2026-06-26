package com.viel.oto.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Branch/boundary coverage for [PlaybackMediaId].
 *
 * Real API under test:
 *   - compose(bookId, fileId): String  -> "oto-mid:v1:<bookId.length>:<bookId><fileId>"
 *   - parse(mediaId: String?): Parts?
 *
 * Expected values were derived by stepping through parseV1 against the PREFIX
 * "oto-mid:v1:" (length 11, so indexOf(':') starts at index 11).
 */
class PlaybackMediaIdTest {

    // ---- compose format ------------------------------------------------------

    @Test
    fun `compose emits length-prefixed v1 schema`() {
        assertEquals("oto-mid:v1:6:book-1file-1", PlaybackMediaId.compose("book-1", "file-1"))
    }

    @Test
    fun `compose with colon-bearing ids records book length`() {
        // bookId "a:b" length = 3 -> "oto-mid:v1:3:a:bc:d:e"
        assertEquals("oto-mid:v1:3:a:bc:d:e", PlaybackMediaId.compose("a:b", "c:d:e"))
    }

    @Test
    fun `compose with empty book id uses zero length`() {
        assertEquals("oto-mid:v1:0:file", PlaybackMediaId.compose("", "file"))
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

    // ---- length segment is not an integer (toIntOrNull) ---------------------

    @Test
    fun `parse with non-numeric length returns null`() {
        assertNull(PlaybackMediaId.parse("oto-mid:v1:x:bookfile"))
    }

    // ---- length <= 0 ---------------------------------------------------------

    @Test
    fun `parse with zero length returns null`() {
        // bookIdLength == 0 -> bookIdLength <= 0 branch
        assertNull(PlaybackMediaId.parse("oto-mid:v1:0:file"))
    }

    @Test
    fun `parse with negative length returns null`() {
        // "-1" parses as -1 -> bookIdLength <= 0 branch
        assertNull(PlaybackMediaId.parse("oto-mid:v1:-1:abcd"))
    }

    // ---- bookIdEnd > length --------------------------------------------------

    @Test
    fun `parse with length exceeding remaining text returns null`() {
        // bookIdLength = 9 but only 2 chars remain -> bookIdEnd > mediaId.length
        assertNull(PlaybackMediaId.parse("oto-mid:v1:9:ab"))
    }

    // ---- bookId blank --------------------------------------------------------

    @Test
    fun `parse with whitespace-only book id returns null`() {
        // bookId "   " is non-empty but blank -> bookId.isBlank() branch
        assertNull(PlaybackMediaId.parse("oto-mid:v1:3:   file"))
    }

    // ---- fileId blank --------------------------------------------------------

    @Test
    fun `parse with empty file id returns null`() {
        // length exactly consumes the rest, leaving fileId == "" -> fileId.isBlank()
        assertNull(PlaybackMediaId.parse("oto-mid:v1:4:book"))
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
}
