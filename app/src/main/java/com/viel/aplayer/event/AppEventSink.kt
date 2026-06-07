package com.viel.aplayer.event

import com.viel.aplayer.event.feedback.DropReason
import com.viel.aplayer.event.feedback.FeedbackDeliveryDecision
import com.viel.aplayer.event.feedback.FeedbackDeliveryResult
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.event.feedback.TransientFeedbackDeliveryPolicy
import com.viel.aplayer.event.feedback.TransientFeedbackFact
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Application Event Sink (Centralizes transient UI feedback delivery)
 *
 * This interface gives ViewModels, application services, and infrastructure coordinators one app-level
 * event entry point so they no longer route toast commands through playback-core classes.
 */
interface AppEventSink {
    val events: SharedFlow<AppShellEvent>

    /**
     * Emit Transient Feedback Fact (Publishes resource-keyed feedback through the shared policy)
     *
     * Returning the structured delivery result lets callers and tests distinguish delivered, merged, and
     * dropped feedback without depending on SharedFlow implementation details.
     */
    fun emitFeedback(fact: TransientFeedbackFact): FeedbackDeliveryResult

    /**
     * Toast Feedback Shortcut (Keeps callers away from concrete AppShellEvent construction)
     *
     * Application callers describe a resource-backed message; the app shell remains responsible for
     * resolving localized text and rendering the actual Toast widget.
     */
    fun showToast(message: FeedbackMessage): FeedbackDeliveryResult =
        emitFeedback(TransientFeedbackFact(message))

    /**
     * Legacy Toast Shortcut (Keeps unmigrated callers explicit and searchable)
     *
     * Raw text remains supported during incremental migration, but resource-backed message keys should be
     * preferred for new feedback paths.
     */
    fun showToast(message: String): FeedbackDeliveryResult = showToast(FeedbackMessages.rawText(message))

    /**
     * Track Recovery Dialog Shortcut (Represents the only app-shell dialog event currently shared by playback)
     *
     * The shortcut keeps media adapters from importing UI event classes while preserving the existing dialog trigger.
     */
    fun showTrackUnavailableDialog(bookId: String, queueIndex: Int): Boolean
}

/**
 * Default Application Event Sink (Buffered hot stream for process-wide one-shot events)
 *
 * A small overflow buffer absorbs short bursts while the Compose app shell collector is active; feedback
 * emitted without a collector is reported as dropped instead of pretending tryEmit guaranteed visibility.
 */
class DefaultAppEventSink(
    private val feedbackPolicy: TransientFeedbackDeliveryPolicy = TransientFeedbackDeliveryPolicy()
) : AppEventSink {
    private val _events = MutableSharedFlow<AppShellEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val events: SharedFlow<AppShellEvent> = _events.asSharedFlow()

    override fun emitFeedback(fact: TransientFeedbackFact): FeedbackDeliveryResult =
        if (_events.subscriptionCount.value == 0) {
            FeedbackDeliveryResult.Dropped(fact, DropReason.NO_COLLECTOR)
        } else {
            when (val decision = feedbackPolicy.evaluate(fact)) {
                FeedbackDeliveryDecision.Deliver -> {
                    if (_events.tryEmit(AppShellEvent.ShowToast(fact.message))) {
                        FeedbackDeliveryResult.Delivered(fact)
                    } else {
                        FeedbackDeliveryResult.Dropped(fact, DropReason.STREAM_REJECTED)
                    }
                }
                FeedbackDeliveryDecision.Merged -> FeedbackDeliveryResult.Merged(fact)
                is FeedbackDeliveryDecision.Dropped -> FeedbackDeliveryResult.Dropped(fact, decision.reason)
            }
        }

    override fun showTrackUnavailableDialog(bookId: String, queueIndex: Int): Boolean =
        _events.subscriptionCount.value > 0 &&
            _events.tryEmit(AppShellEvent.ShowTrackUnavailableDialog(bookId, queueIndex))
}
