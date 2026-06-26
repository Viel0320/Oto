package com.viel.oto.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ensures Room serialization mapping stays robust.
 * Verifies enums translate correctly to strings, and that invalid strings fallback gracefully.
 */
class AudiobookDatabaseConvertersTest {

    private val converters = AudiobookDatabaseConverters()

    @Test
    fun testAbsPlaybackSessionStateConversion() {
        assertEquals("OPEN", converters.fromAbsPlaybackSessionState(AudiobookSchema.AbsPlaybackSessionState.OPEN))
        assertEquals("SYNCED", converters.fromAbsPlaybackSessionState(AudiobookSchema.AbsPlaybackSessionState.SYNCED))

        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState("OPEN"))
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.SYNCED, converters.toAbsPlaybackSessionState("SYNCED"))

        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState("INVALID"))
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState(""))
    }
}
