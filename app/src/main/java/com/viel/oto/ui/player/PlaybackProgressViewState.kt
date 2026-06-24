package com.viel.oto.ui.player

/**
 * Playback progress projection for UI surfaces that need high-frequency position updates.
 *
 * Keeping this as a small top-level UI state lets progress consumers collect only the playback
 * coordinates they need without depending on the full player ViewModel or forcing parent layout
 * templates to recompose on every playback tick.
 */
data class PlaybackProgressViewState(
    val elapsedMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
    val isChapterProgressMode: Boolean = false
)
