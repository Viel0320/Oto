package com.viel.aplayer.widget

import com.viel.aplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerWidgetPlaybackPresentationTest {

    @Test
    fun playingStateAnnouncesPauseAction() {
        // Widget Accessibility Regression (Playing state must expose the pause action because TalkBack announces what activation will do)
        // This locks the widget state label to the same action-specific resource used by the rest of playback controls.
        assertEquals(
            R.string.playback_pause_content_description,
            PlayerWidgetPlaybackPresentation.playPauseContentDescription(isPlaying = true)
        )
    }

    @Test
    fun stoppedStateAnnouncesPlayAction() {
        // Widget Accessibility Regression (Stopped state must expose the play action because TalkBack announces the next available control action)
        // This guards against reverting to the generic widget toggle label when the widget is not actively playing.
        assertEquals(
            R.string.playback_play_content_description,
            PlayerWidgetPlaybackPresentation.playPauseContentDescription(isPlaying = false)
        )
    }
}
