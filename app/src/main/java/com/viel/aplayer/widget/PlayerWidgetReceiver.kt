package com.viel.aplayer.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Desktop widget lifecycle bridge receiver.
 *
 * Core Responsibilities:
 * 1. Extends GlanceAppWidgetReceiver to bind and manage the rendering lifecycle and APPWIDGET_UPDATE broadcasts for the PlayerWidget view.
 * 2. Enforces component isolation: custom playback command logic has been decoupled from this receiver and moved into the non-exported PlayerWidgetActionReceiver.
 *    This receiver only handles standard system widget update behaviors, protecting the playback control pipeline from malicious or fake external broadcasts.
 */
class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = PlayerWidget()
}
