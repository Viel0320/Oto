package com.viel.aplayer.event

import com.viel.aplayer.R
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.media.PlaybackDomainEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFeedbackMappingTest {
    @Test
    fun `playback facts map to resource feedback keys`() {
        // Playback Message Key Mapping (Locks the bridge to resource-backed feedback instead of hard-coded copy)
        // The app shell can localize this key later while media callers keep emitting domain facts.
        val message = PlaybackDomainEvent.PlaybackFinishedShutdownScheduled(delaySeconds = 5)
            .toFeedbackMessage()

        val resource = message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_playback_finished_shutdown_scheduled, resource.resId)
        assertEquals(listOf(5), resource.args)
    }

    @Test
    fun `playback source preflight keeps details as render arguments`() {
        // Dynamic Detail Mapping (Keeps preflight detail text as a rendering argument)
        // The resource key stays stable even when the unavailable root explanation varies at runtime.
        val message = PlaybackDomainEvent.SourcePreflightBlocked("媒体库根不可用")
            .toFeedbackMessage()

        val resource = message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_playback_source_preflight_blocked, resource.resId)
        assertEquals(listOf("媒体库根不可用"), resource.args)
    }

    @Test
    fun `settings root unavailable feedback keeps dynamic detail behind a resource key`() {
        // Settings Dynamic Detail Mapping (Prevents manual sync preflight feedback from falling back to raw text)
        // The detailed availability explanation remains data while the app shell owns the localized wrapper.
        val message = FeedbackMessages.settingsRootUnavailableSyncBlocked("库根不可用，已跳过同步：Shelf")

        val resource = message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_settings_root_unavailable_sync_blocked, resource.resId)
        assertEquals(listOf("库根不可用，已跳过同步：Shelf"), resource.args)
    }
}
