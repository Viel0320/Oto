package com.viel.aplayer.application.di.dependencies

import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.event.AppEventSink

/**
 * App Shell Dependencies (Top-level Compose shell dependency view)
 * Limits APlayerApp to global settings and app-shell feedback collection.
 */
// Title: AppShellDependencies Interface Decoupling (Expose settings read model abstraction to navigation root)
// This replacement ensures the application shell resolves preferences via a read model instead of a data storage implementation.
interface AppShellDependencies {
    /**
     * Settings Read Model (App shell theme and behavior preference query stream)
     * Provides the initial settings state required before rendering navigation content.
     */
    val settingsReadModel: AppSettingsReadModel

    /**
     * Application Event Sink (App shell transient feedback stream)
     * Gives the shell one stream to render Toasts and dialogs from process-wide events.
     */
    val appEventSink: AppEventSink
}

/**
 * App Feedback Dependencies (Composable feedback-only dependency view)
 * Gives small UI helpers only the shared app event sink when they need to publish transient messages.
 */
interface AppFeedbackDependencies {
    /**
     * Application Event Sink (Feedback-only command seam)
     * Keeps UI helpers from resolving the complete application container for one Toast command.
     */
    val appEventSink: AppEventSink
}
