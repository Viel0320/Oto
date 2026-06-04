package com.viel.aplayer.ui.common

/**
 * UI Event Interface: Represents lightweight, single-use feedback signals from ViewModel to UI.
 *
 * Adheres to standard MVI (Model-View-Intent) or Unidirectional Data Flow guidelines for one-off events.
 * Placed within `ui.common` package to allow reuse across feature components, eliminating boilerplate code.
 */
sealed interface UiEvent {
    /**
     * Show Toast Event: Denotes a message to be briefly displayed as a transient toast.
     *
     * @param message The text content of the message.
     */
    data class ShowToast(val message: String) : UiEvent

    /**
     * Track Unavailable Event: Triggers a dialog prompt to notify the listener that the current track is missing or corrupt.
     *
     * @param bookId The unique identifier of the target audiobook.
     * @param queueIndex The index of the failing audio track within the playback queue.
     */
    data class ShowTrackUnavailableDialog(val bookId: String, val queueIndex: Int) : UiEvent
}
