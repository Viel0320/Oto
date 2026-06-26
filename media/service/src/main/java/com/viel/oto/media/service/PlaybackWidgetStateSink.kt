package com.viel.oto.media.service

import android.content.Context

/**
 * Receives playback snapshots that a widget implementation may choose to render.
 *
 * PlaybackService only knows the state projection. The app/widget layer owns Glance ids, widget
 * storage, and render invalidation so the service module does not depend on widget classes.
 */
interface PlaybackWidgetStateSink {
    suspend fun update(context: Context, snapshot: PlaybackWidgetSnapshot)

    object NoOp : PlaybackWidgetStateSink {
        override suspend fun update(context: Context, snapshot: PlaybackWidgetSnapshot) = Unit
    }
}

data class PlaybackWidgetSnapshot(
    val isPlaying: Boolean,
    val title: String?,
    val author: String?,
    val coverPath: String?,
    val seekBackwardSeconds: Int,
    val seekForwardSeconds: Int
)
