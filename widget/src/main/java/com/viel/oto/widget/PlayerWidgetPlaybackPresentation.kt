package com.viel.oto.widget

internal object PlayerWidgetPlaybackPresentation {

    fun playPauseIcon(isPlaying: Boolean): Int =
        if (isPlaying) R.drawable.pause else R.drawable.play

    fun playPauseContentDescription(isPlaying: Boolean): Int =
        if (isPlaying) {
            R.string.playback_pause_content_description
        } else {
            R.string.playback_play_content_description
        }
}
