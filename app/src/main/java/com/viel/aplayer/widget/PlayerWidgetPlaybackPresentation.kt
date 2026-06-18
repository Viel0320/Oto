package com.viel.aplayer.widget

import com.viel.aplayer.R

internal object PlayerWidgetPlaybackPresentation {

    // Widget Play/Pause Icon Resolver (Keeps the runtime widget icon aligned with the current playback state)
    // Playing content exposes a pause glyph because pressing the control will pause playback; idle content exposes a play glyph because pressing it will start playback.
    fun playPauseIcon(isPlaying: Boolean): Int =
        if (isPlaying) R.drawable.pause else R.drawable.play

    // Widget Play/Pause Accessibility Resolver (Announces the concrete action TalkBack will trigger instead of a generic toggle label)
    // The string resource IDs reuse the shared playback accessibility copy so every supported app locale receives the same action-specific wording.
    fun playPauseContentDescription(isPlaying: Boolean): Int =
        if (isPlaying) {
            R.string.playback_pause_content_description
        } else {
            R.string.playback_play_content_description
        }
}
