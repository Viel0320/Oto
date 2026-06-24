package com.viel.oto.event

import com.viel.oto.event.feedback.DropReason
import com.viel.oto.event.feedback.FeedbackAggregationIdentity
import com.viel.oto.event.feedback.FeedbackCategory
import com.viel.oto.event.feedback.FeedbackContext
import com.viel.oto.event.feedback.FeedbackDeliveryResult
import com.viel.oto.event.feedback.FeedbackFact
import com.viel.oto.event.feedback.FeedbackLifecycle
import com.viel.oto.event.feedback.FeedbackMessages
import com.viel.oto.event.feedback.FeedbackOutcome
import com.viel.oto.event.feedback.FeedbackRenderMode
import com.viel.oto.event.feedback.FeedbackSeverity
import com.viel.oto.event.feedback.FeedbackTopic
import com.viel.oto.event.feedback.RecoveryFeedbackFacts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class AppEventSinkTest {
    @Test
    fun `app feedback reports dropped when no shell collector is active`() {
        val sink = DefaultAppEventSink()
        val result = sink.emitFeedback(downloadFinal())

        assertTrue(result is FeedbackDeliveryResult.Dropped)
        assertEquals(DropReason.NO_COLLECTOR, (result as FeedbackDeliveryResult.Dropped).reason)
    }

    @Test
    fun `final feedback with an active collector reaches the toast event`() = runTest {
        val sink = DefaultAppEventSink(scope = backgroundScope)
        val received = mutableListOf<AppShellEvent>()
        backgroundScope.launch { sink.events.toList(received) }
        runCurrent()

        val result = sink.emitFeedback(downloadFinal())
        runCurrent()

        assertTrue(result is FeedbackDeliveryResult.Delivered)
        assertEquals(1, received.size)
        val event = received.first() as AppShellEvent.RenderFeedback
        assertEquals(FeedbackRenderMode.TOAST, event.renderMode)
    }

    @Test
    fun `provisional feedback is held then rendered after the hold`() = runTest {
        val sink = DefaultAppEventSink(scope = backgroundScope)
        val received = mutableListOf<AppShellEvent>()
        backgroundScope.launch { sink.events.toList(received) }
        runCurrent()

        val result = sink.emitFeedback(speedProvisional())
        runCurrent()
        assertTrue(result is FeedbackDeliveryResult.Held)
        assertEquals(0, received.size)

        advanceTimeBy(400.milliseconds)
        runCurrent()
        assertEquals(1, received.size)
        val event = received.first() as AppShellEvent.RenderFeedback
        assertEquals(FeedbackRenderMode.TOAST, event.renderMode)
    }

    @Test
    fun `final cancels a same-identity pending provisional so only the final renders`() = runTest {
        val sink = DefaultAppEventSink(scope = backgroundScope)
        val received = mutableListOf<AppShellEvent>()
        backgroundScope.launch { sink.events.toList(received) }
        runCurrent()

        assertTrue(sink.emitFeedback(speedProvisional()) is FeedbackDeliveryResult.Held)
        runCurrent()
        assertTrue(sink.emitFeedback(speedFinal()) is FeedbackDeliveryResult.Delivered)
        runCurrent()

        advanceTimeBy(400.milliseconds)
        runCurrent()
        assertEquals(1, received.size)
    }

    @Test
    fun `dialog feedback with an active collector reaches the dialog event`() = runTest {
        val sink = DefaultAppEventSink(scope = backgroundScope)
        val received = mutableListOf<AppShellEvent>()
        backgroundScope.launch { sink.events.toList(received) }
        runCurrent()

        val result = sink.emitFeedback(RecoveryFeedbackFacts.trackUnavailable("book-1", 2))
        runCurrent()

        assertTrue(result is FeedbackDeliveryResult.Delivered)
        assertEquals(1, received.size)
        val event = received.first() as AppShellEvent.RenderFeedback
        assertEquals(FeedbackRenderMode.DIALOG, event.renderMode)
    }

    @Test
    fun `dialog feedback uses the shared delivery policy for final merge`() = runTest {
        val sink = DefaultAppEventSink(scope = backgroundScope)
        val received = mutableListOf<AppShellEvent>()
        backgroundScope.launch { sink.events.toList(received) }
        runCurrent()

        assertTrue(sink.emitFeedback(RecoveryFeedbackFacts.trackUnavailable("book-1", 2)) is FeedbackDeliveryResult.Delivered)
        assertTrue(sink.emitFeedback(RecoveryFeedbackFacts.trackUnavailable("book-1", 2)) is FeedbackDeliveryResult.Merged)
        runCurrent()

        assertEquals(1, received.size)
        val event = received.first() as AppShellEvent.RenderFeedback
        assertEquals(FeedbackRenderMode.DIALOG, event.renderMode)
    }

    @Test
    fun `dialog provisional feedback is held then rendered as dialog`() = runTest {
        val sink = DefaultAppEventSink(scope = backgroundScope)
        val received = mutableListOf<AppShellEvent>()
        backgroundScope.launch { sink.events.toList(received) }
        runCurrent()

        val result = sink.emitFeedback(dialogProvisional())
        runCurrent()
        assertTrue(result is FeedbackDeliveryResult.Held)
        assertEquals(0, received.size)

        advanceTimeBy(400.milliseconds)
        runCurrent()

        assertEquals(1, received.size)
        val event = received.first() as AppShellEvent.RenderFeedback
        assertEquals(FeedbackRenderMode.DIALOG, event.renderMode)
    }

    private fun speedProvisional() = playbackControl(FeedbackLifecycle.PROVISIONAL)
    private fun speedFinal() = playbackControl(FeedbackLifecycle.FINAL)

    private fun playbackControl(lifecycle: FeedbackLifecycle) = FeedbackFact(
        message = FeedbackMessages.playbackBookmarkCreated(),
        outcome = FeedbackOutcome(
            identity = FeedbackAggregationIdentity(
                category = FeedbackCategory.PLAYBACK_CONTROL,
                topic = FeedbackTopic.PlaybackSpeed,
                context = FeedbackContext.PlaybackControl
            ),
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = lifecycle
        )
    )

    private fun downloadFinal() = FeedbackFact(
        message = FeedbackMessages.playbackBookmarkCreated(),
        outcome = FeedbackOutcome(
            identity = FeedbackAggregationIdentity(
                category = FeedbackCategory.DOWNLOAD_CACHE,
                topic = FeedbackTopic.DownloadCacheTask,
                context = FeedbackContext.DownloadCacheTask(bookId = "book-1")
            ),
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = FeedbackLifecycle.FINAL
        )
    )

    private fun dialogProvisional() = FeedbackFact(
        message = FeedbackMessages.playbackBookmarkCreated(),
        outcome = FeedbackOutcome(
            identity = FeedbackAggregationIdentity(
                category = FeedbackCategory.RECOVERY,
                topic = FeedbackTopic.PlaybackContentAvailability,
                context = FeedbackContext.PlaybackContent(bookId = "book-1", queueIndex = 2)
            ),
            severity = FeedbackSeverity.BLOCKED,
            lifecycle = FeedbackLifecycle.PROVISIONAL
        ),
        renderMode = FeedbackRenderMode.DIALOG
    )
}
