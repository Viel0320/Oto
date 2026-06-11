package com.viel.aplayer.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AudiobookDatabaseConverters Tests (Ensures Room serialization mapping stays robust)
 * Verifies enums translate correctly to strings, and that invalid strings fallback gracefully.
 */
class AudiobookDatabaseConvertersTest {

    private val converters = AudiobookDatabaseConverters()

    @Test
    fun testAbsPlaybackSessionStateConversion() {
        // Test Normal Serialization (Serialize enum values to expected String representations)
        assertEquals("OPEN", converters.fromAbsPlaybackSessionState(AudiobookSchema.AbsPlaybackSessionState.OPEN))
        assertEquals("SYNCED", converters.fromAbsPlaybackSessionState(AudiobookSchema.AbsPlaybackSessionState.SYNCED))

        // Test Normal Deserialization (Deserialize valid String values back to exact enum keys)
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState("OPEN"))
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.SYNCED, converters.toAbsPlaybackSessionState("SYNCED"))

        // Test Fallback Deserialization (Deserialize invalid/unsupported String to a safe fallback OPEN state)
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState("INVALID"))
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState(""))
    }
}
