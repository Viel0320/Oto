package com.viel.aplayer.event.feedback

/**
 * Feedback Delivery Result (Observable outcome for transient event publication)
 *
 * Returning this value makes delivery policy testable and prevents callers from treating SharedFlow
 * `tryEmit` as a guaranteed user-visible notification.
 */
sealed interface FeedbackDeliveryResult {
    val fact: TransientFeedbackFact
    val delivered: Boolean

    /**
     * Delivered Feedback (The fact was accepted into the event stream)
     *
     * This does not promise Android has rendered the Toast yet; it only confirms the stream accepted the
     * fact after merge and burst policies were applied.
     */
    data class Delivered(
        override val fact: TransientFeedbackFact
    ) : FeedbackDeliveryResult {
        override val delivered: Boolean = true
    }

    /**
     * Merged Feedback (A duplicate fact was suppressed by the merge window)
     *
     * The caller can observe that the request was understood, but no additional app-shell event was
     * emitted because an equivalent feedback fact was already recent.
     */
    data class Merged(
        override val fact: TransientFeedbackFact
    ) : FeedbackDeliveryResult {
        override val delivered: Boolean = false
    }

    /**
     * Dropped Feedback (The fact could not enter the transient feedback stream)
     *
     * The reason explains whether the drop came from local burst policy or the underlying hot stream.
     */
    data class Dropped(
        override val fact: TransientFeedbackFact,
        val reason: DropReason
    ) : FeedbackDeliveryResult {
        override val delivered: Boolean = false
    }
}

/**
 * Feedback Drop Reason (Stable diagnostics for suppressed transient feedback)
 *
 * Tests assert these values instead of matching log text or relying on SharedFlow implementation details.
 */
enum class DropReason {
    NO_COLLECTOR,
    BURST_LIMIT,
    STREAM_REJECTED
}
