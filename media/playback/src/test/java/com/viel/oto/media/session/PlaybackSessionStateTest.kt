package com.viel.oto.media.session

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSessionStateTest {
    @Test
    fun `error before playback is classified as initial load failure`() {
        val state = PlaybackSessionState()

        assertEquals(
            PlaybackSessionErrorDecision.InitialMediaLoadFailure,
            state.classifyPlayerError()
        )
    }

    @Test
    fun `playing callback promotes later errors to runtime recovery`() {
        val state = PlaybackSessionState()

        state.onIsPlayingChanged(isPlaying = true)

        assertEquals(
            PlaybackSessionErrorDecision.RuntimePlaybackFailure,
            state.classifyPlayerError()
        )
    }

    @Test
    fun `ready while already playing covers continuous queue transitions`() {
        val state = PlaybackSessionState()

        state.onPlaybackStateChanged(isReady = true, isPlaying = true)

        assertEquals(
            PlaybackSessionErrorDecision.RuntimePlaybackFailure,
            state.classifyPlayerError()
        )
    }

    @Test
    fun `media item transition resets first-frame recovery boundary`() {
        val state = PlaybackSessionState()

        state.onIsPlayingChanged(isPlaying = true)
        state.onMediaItemTransition()

        assertEquals(
            PlaybackSessionErrorDecision.InitialMediaLoadFailure,
            state.classifyPlayerError()
        )
    }
}
