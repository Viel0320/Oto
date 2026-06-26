package com.viel.oto.event

import com.viel.oto.event.feedback.DropReason
import com.viel.oto.event.feedback.FeedbackDeliveryDecision
import com.viel.oto.event.feedback.FeedbackDeliveryPolicy
import com.viel.oto.event.feedback.FeedbackDeliveryResult
import com.viel.oto.event.feedback.FeedbackFact
import com.viel.oto.event.feedback.FeedbackRenderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Centralizes app-shell feedback delivery.
 *
 * This interface gives ViewModels, application services, and infrastructure coordinators one app-level
 * event entry point so they emit feedback facts instead of routing Toast or Dialog commands directly.
 */
interface AppEventSink {
    val events: SharedFlow<AppShellEvent>

    /**
     * Publishes one resource-keyed feedback fact through the selected render mode.
     *
     * Returning the structured delivery result lets callers and tests distinguish delivered, merged, and
     * dropped feedback without depending on SharedFlow implementation details.
     */
    fun emitFeedback(fact: FeedbackFact): FeedbackDeliveryResult

}

/**
 * Buffered hot stream for process-wide feedback events.
 *
 * A small overflow buffer absorbs short bursts while the Compose app shell collector is active. Every
 * feedback fact enters the same delivery policy before its mutually exclusive render mode decides whether
 * the shell consumes it as a Toast or a Dialog.
 */
class DefaultAppEventSink(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val deliveryPolicy: FeedbackDeliveryPolicy = FeedbackDeliveryPolicy()
) : AppEventSink {
    private val _events = MutableSharedFlow<AppShellEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val events: SharedFlow<AppShellEvent> = _events.asSharedFlow()

    override fun emitFeedback(fact: FeedbackFact): FeedbackDeliveryResult {
        if (_events.subscriptionCount.value == 0) {
            return FeedbackDeliveryResult.Dropped(fact, DropReason.NO_COLLECTOR)
        }
        return when (val decision = deliveryPolicy.evaluate(fact)) {
            FeedbackDeliveryDecision.Deliver -> emitFeedbackRender(fact, fact.renderMode)
            FeedbackDeliveryDecision.Merged -> FeedbackDeliveryResult.Merged(fact)
            is FeedbackDeliveryDecision.Dropped -> FeedbackDeliveryResult.Dropped(fact, decision.reason)
            is FeedbackDeliveryDecision.Hold -> {
                scheduleRelease(fact, decision)
                FeedbackDeliveryResult.Held(fact)
            }
        }
    }

    /**
     * Renders a held provisional only if it survives the hold.
     *
     * The delayed task runs on the injected scope so graph teardown can cancel any unrendered provisional.
     * A provisional replaced by a newer one or cancelled by a final resolves to merged in the delivery
     * policy and never reaches the stream.
     */
    private fun scheduleRelease(fact: FeedbackFact, hold: FeedbackDeliveryDecision.Hold) {
        scope.launch {
            delay(hold.holdMs)
            if (_events.subscriptionCount.value == 0) return@launch
            if (deliveryPolicy.releasePending(fact, hold.generation) == FeedbackDeliveryDecision.Deliver) {
                emitFeedbackRender(fact, fact.renderMode)
            }
        }
    }

    private fun emitFeedbackRender(
        fact: FeedbackFact,
        renderMode: FeedbackRenderMode
    ): FeedbackDeliveryResult =
        if (_events.tryEmit(AppShellEvent.RenderFeedback(fact, renderMode))) {
            FeedbackDeliveryResult.Delivered(fact)
        } else {
            FeedbackDeliveryResult.Dropped(fact, DropReason.STREAM_REJECTED)
        }
}
