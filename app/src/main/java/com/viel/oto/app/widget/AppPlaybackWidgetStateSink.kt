package com.viel.oto.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.viel.oto.media.service.PlaybackWidgetSnapshot
import com.viel.oto.media.service.PlaybackWidgetStateSink
import com.viel.oto.widget.PlayerWidget
import com.viel.oto.widget.PlayerWidgetStateHelper

/**
 * Routes playback snapshots from the service module into the extracted Glance widget store.
 *
 * The app composition root owns this adapter while media service and widget remain separate modules:
 * service emits a normalized snapshot, and widget owns Glance ids, storage, and render invalidation.
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
