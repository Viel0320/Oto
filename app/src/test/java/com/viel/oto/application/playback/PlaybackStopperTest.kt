package com.viel.oto.application.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks conditional playback shutdown behavior.
 * Verifies destructive workflows can stop only the matching active audiobook without depending on media runtime classes.
 */
class PlaybackStopperTest {

    @Test
    fun currentPlayingBookMatchTriggersStop() = runBlocking {
        val stopper = RecordingPlaybackStopper(currentBookId = BOOK_ID)

        val stopped = stopper.stopIfPlaying(BOOK_ID)

        assertTrue(stopped)
        assertEquals(1, stopper.stopCount)
    }

    @Test
    fun differentCurrentPlayingBookDoesNotTriggerStop() = runBlocking {
        val stopper = RecordingPlaybackStopper(currentBookId = "other-book")

        val stopped = stopper.stopIfPlaying(BOOK_ID)

        assertFalse(stopped)
        assertEquals(0, stopper.stopCount)
    }

    private class RecordingPlaybackStopper(
        currentBookId: String?
    ) : PlaybackStopper {
        override var currentPlayingBookId: String? = currentBookId
        var stopCount = 0

        override suspend fun stopPlayback() {
            stopCount += 1
        }
    }

    private companion object {
        private const val BOOK_ID = "book-id"
    }
}
