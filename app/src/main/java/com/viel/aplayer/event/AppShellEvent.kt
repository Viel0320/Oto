package com.viel.aplayer.event

import com.viel.aplayer.event.feedback.FeedbackMessage

/**
 * App Shell Event (One-shot presentation commands consumed by the top-level app shell)
 *
 * This event model lives in the application event package because it represents rendering decisions
 * owned by `APlayerApp`, not feature-local ViewModel events or media-core domain facts.
 */
sealed interface AppShellEvent {
    /**
     * Show Toast Event (Requests a transient app-shell toast)
     *
     * Callers provide a resource-backed feedback message; the app shell decides how the Toast is
     * localized and rendered.
     *
     * @param message The feedback message key and render arguments.
     */
    data class ShowToast(val message: FeedbackMessage) : AppShellEvent

    /**
     * Track Unavailable Dialog Event (Requests the existing playback recovery confirmation dialog)
     *
     * Playback emits domain facts first; the application bridge converts them into this app-shell dialog
     * command so media-core modules do not depend on UI event types.
     *
     * @param bookId The unique identifier of the target audiobook.
     * @param queueIndex The index of the failing audio track within the playback queue.
     */
    data class ShowTrackUnavailableDialog(val bookId: String, val queueIndex: Int) : AppShellEvent
}
