package com.viel.aplayer.event

import com.viel.aplayer.R
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.media.PlaybackDomainEvent
import com.viel.aplayer.media.PlaybackSourcePreflightBlockReason
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFeedbackMappingTest {
    @Test
    fun `playback facts map to resource feedback keys`() {
        // Playback Message Key Mapping (Locks the bridge to resource-backed feedback instead of hard-coded copy)
        // The app shell can localize this key later while media callers keep emitting domain facts.
        val message = PlaybackDomainEvent.PlaybackFinishedShutdownScheduled(delaySeconds = 5)
            .toFeedbackMessage()

        val resource = message as FeedbackMessage.Quantity
        // Counted Playback Feedback Mapping (Keep shutdown delay feedback on Android plural resources)
        // The bridge must preserve the delay quantity separately from format arguments so localized plural rules can render the toast.
        assertEquals(R.plurals.feedback_playback_finished_shutdown_scheduled, resource.resId)
        assertEquals(5, resource.quantity)
        assertEquals(listOf(5), resource.args)
    }

    @Test
    fun `playback source preflight maps typed block reasons to resource feedback`() {
        // Typed Preflight Mapping (Keeps media-domain failure reasons language-neutral)
        // The bridge translates a stable reason plus root name into the localized app-shell feedback key.
        val message = PlaybackDomainEvent.SourcePreflightBlocked(
            reason = PlaybackSourcePreflightBlockReason.UnavailableRoot,
            rootName = "Shelf"
        ).toFeedbackMessage()

        val resource = message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_playback_source_preflight_unavailable_root, resource.resId)
        assertEquals(listOf("Shelf"), resource.args)
    }

    @Test
    fun `settings root unavailable feedback preserves resource backed detail`() {
        // Settings Detail Preservation (Avoids wrapping already localized sync preflight feedback in raw text)
        // Manual sync callers now pass the resource-backed detail through unchanged so availability codes stay typed.
        val detail = FeedbackMessage.Resource(
            R.string.feedback_sync_root_unavailable_not_found,
            listOf("Shelf")
        )
        val message = FeedbackMessages.settingsRootUnavailableSyncBlocked(detail)

        val resource = message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_sync_root_unavailable_not_found, resource.resId)
        assertEquals(listOf("Shelf"), resource.args)
    }
}
