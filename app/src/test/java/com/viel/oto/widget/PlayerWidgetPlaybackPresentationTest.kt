package com.viel.oto.widget

import com.viel.oto.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerWidgetPlaybackPresentationTest {

    @Test
    fun playingStateAnnouncesPauseAction() {
        assertEquals(
            R.string.playback_pause_content_description,
            PlayerWidgetPlaybackPresentation.playPauseContentDescription(isPlaying = true)
        )
    }

    @Test
    fun stoppedStateAnnouncesPlayAction() {
        assertEquals(
            R.string.playback_play_content_description,
            PlayerWidgetPlaybackPresentation.playPauseContentDescription(isPlaying = false)
        )
    }
}
