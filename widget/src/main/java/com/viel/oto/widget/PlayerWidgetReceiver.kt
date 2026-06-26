package com.viel.oto.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Desktop widget lifecycle bridge receiver.
 *
 * Handles standard Glance widget update lifecycle events only.
 *
 * Playback commands are handled by PlayerWidgetActionReceiver, which is non-exported and scoped to
 * app-authored PendingIntents.
 */
class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = PlayerWidget()
}
