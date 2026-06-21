package com.viel.aplayer.di.dependencies

import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.event.AppEventSink

/**
 * Top-level Compose shell dependency view.
 * Limits APlayerApp to global settings and app-shell feedback collection.
 */
interface AppShellDependencies {
    /**
     * App shell theme and behavior preference query stream.
     * Provides the initial settings state required before rendering navigation content.
     */
    val settingsReadModel: AppSettingsReadModel

    /**
     * App shell feedback render stream.
     * Gives the shell one stream to render Toasts and dialogs from process-wide events.
     */
    val appEventSink: AppEventSink
}

/**
 * Composable feedback-only dependency view.
 * Gives small UI helpers only the shared app event sink when they need to publish transient messages.
 */
interface AppFeedbackDependencies {
    /**
     * Feedback-only command seam.
     * Keeps UI helpers from resolving the complete application container for one Toast command.
     */
    val appEventSink: AppEventSink
}
