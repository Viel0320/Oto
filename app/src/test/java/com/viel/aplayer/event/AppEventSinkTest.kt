package com.viel.aplayer.event

import com.viel.aplayer.event.feedback.DropReason
import com.viel.aplayer.event.feedback.FeedbackDeliveryResult
import com.viel.aplayer.event.feedback.FeedbackMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEventSinkTest {
    @Test
    fun `app feedback reports dropped when no shell collector is active`() {
        // No Collector Scenario (Protects against SharedFlow.tryEmit pretending an unobserved event was visible)
        // The app shell owns rendering, so feedback emitted before that collector exists must be observable as dropped.
        val sink = DefaultAppEventSink()
        val result = sink.showToast(FeedbackMessages.playbackBookmarkCreated())

        assertTrue(result is FeedbackDeliveryResult.Dropped)
        assertEquals(DropReason.NO_COLLECTOR, (result as FeedbackDeliveryResult.Dropped).reason)
    }
}
