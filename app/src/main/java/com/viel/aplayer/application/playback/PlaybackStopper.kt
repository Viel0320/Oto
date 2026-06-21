package com.viel.aplayer.application.playback

/**
 * Minimal playback lifecycle control for destructive library workflows.
 * Lets deletion use cases coordinate active playback shutdown without depending on the media runtime singleton.
 */
interface PlaybackStopper {
    /**
     * Read-only playback ownership probe.
     * Exposes only the active audiobook id needed by deletion policies, keeping queue, controller, and player details hidden.
     */
    val currentPlayingBookId: String?

    /**
     * Foreground playback termination operation.
     * Stops the active playback session through whichever media adapter owns the runtime lifecycle.
     */
    suspend fun stopPlayback()

    /**
     * Targeted destructive-workflow guard.
     * Stops playback only when the active audiobook matches the target being deleted, returning whether a stop was performed.
     */
    suspend fun stopIfPlaying(bookId: String): Boolean {
        val activeBookId = currentPlayingBookId
        if (activeBookId == bookId) {
            stopPlayback()
            return true
        }
        return false
    }
}
