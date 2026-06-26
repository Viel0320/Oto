package com.viel.oto.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.viel.oto.media.service.PlaybackWidgetSnapshot
import com.viel.oto.media.service.PlaybackWidgetStateSink

/**
 * Routes playback snapshots from the service module into the app-owned Glance widget store.
 *
 * Widget identity, Glance APIs, and render state remain in the app/widget layer; the service module
 * only emits the normalized snapshot that was already computed for playback.
 */
class AppPlaybackWidgetStateSink : PlaybackWidgetStateSink {
    override suspend fun update(context: Context, snapshot: PlaybackWidgetSnapshot) {
        val appContext = context.applicationContext
        val glanceIds = GlanceAppWidgetManager(appContext)
            .getGlanceIds(PlayerWidget::class.java)
        if (glanceIds.isEmpty()) return

        PlayerWidgetStateHelper.updateWidgetState(
            context = appContext,
            isPlaying = snapshot.isPlaying,
            title = snapshot.title,
            author = snapshot.author,
            coverPath = snapshot.coverPath,
            seekBackwardSeconds = snapshot.seekBackwardSeconds,
            seekForwardSeconds = snapshot.seekForwardSeconds
        )
    }
}
