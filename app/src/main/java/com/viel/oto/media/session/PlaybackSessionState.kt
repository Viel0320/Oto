package com.viel.oto.media.session

/**
 * Centralizes high-risk playback boundary decisions.
 *
 * Tracks whether the active media item has produced audible playback so service adapters can classify
 * player errors without sharing mutable flags across Media3 callbacks.
 */
class PlaybackSessionState {
    private var currentItemHasProducedPlayback = false

    /**
     * Starts a fresh playback boundary for the next queue item.
     *
     * Resets first-frame tracking because a new item must prove playback before runtime recovery is safe.
     */
    fun onMediaItemTransition() {
        currentItemHasProducedPlayback = false
    }

    /**
     * Records continuous playback when READY arrives while already playing.
     *
     * Covers queue transitions where Media3 keeps playWhenReady active and does not emit a separate
     * isPlaying=true callback before the next item can fail.
     */
    fun onPlaybackStateChanged(isReady: Boolean, isPlaying: Boolean) {
        if (isReady && isPlaying) {
            currentItemHasProducedPlayback = true
        }
    }

    /**
     * Records the first confirmed audible playback frame.
     *
     * Marks the session as runtime playback only after Media3 reports active playback for this item.
     */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            currentItemHasProducedPlayback = true
        }
    }

    /**
     * Separates first-frame failures from runtime stream failures.
     *
     * Initial failures must avoid unavailable-track recovery because the user never reached playback for
     * the current item, while runtime failures may enter the existing recovery path.
     */
    fun classifyPlayerError(): PlaybackSessionErrorDecision =
        if (currentItemHasProducedPlayback) {
            PlaybackSessionErrorDecision.RuntimePlaybackFailure
        } else {
            PlaybackSessionErrorDecision.InitialMediaLoadFailure
        }
}

/**
 * Small event result consumed by service adapters.
 *
 * Keeps the session module interface narrow: callers only learn which recovery path is valid.
 */
sealed interface PlaybackSessionErrorDecision {
    data object InitialMediaLoadFailure : PlaybackSessionErrorDecision
    data object RuntimePlaybackFailure : PlaybackSessionErrorDecision
}
