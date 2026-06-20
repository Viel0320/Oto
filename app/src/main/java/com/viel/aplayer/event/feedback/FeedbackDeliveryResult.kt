package com.viel.aplayer.event.feedback

/**
 * Feedback Delivery Result (Observable outcome for app-shell feedback publication)
 *
 * Returning this value makes delivery policy testable and prevents callers from treating SharedFlow
 * `tryEmit` as a guaranteed user-visible notification. Feedback facts may be merged, held, delivered, or
 * dropped before their render mode is consumed by the app shell.
 */
sealed interface FeedbackDeliveryResult {
    val fact: FeedbackFact
    val delivered: Boolean

    /**
     * Delivered Feedback (The fact was accepted into the event stream)
     *
     * This does not promise Android has rendered the Toast or Dialog yet; it only confirms the stream
     * accepted the fact after the shared delivery policy ran.
     */
    data class Delivered(
        override val fact: FeedbackFact
    ) : FeedbackDeliveryResult {
        override val delivered: Boolean = true
    }

    /**
     * Merged Feedback (A duplicate or superseded fact was suppressed)
     *
     * The caller can observe that the request was understood, but no additional app-shell event was
     * emitted because an equivalent feedback fact was already recent, or a held provisional was replaced
     * or cancelled before its hold expired.
     */
    data class Merged(
        override val fact: FeedbackFact
    ) : FeedbackDeliveryResult {
        override val delivered: Boolean = false
    }

    /**
     * Held Feedback (A provisional fact entered the pending slot but has not rendered)
     *
     * The hold lets a quick task show only its final result. After the hold the fact either renders (no
     * final arrived for the same identity) or resolves to [Merged] (a final or newer provisional took its
     * place). Callers must not treat [Held] as a guaranteed render.
     */
    data class Held(
        override val fact: FeedbackFact
    ) : FeedbackDeliveryResult {
        override val delivered: Boolean = false
    }

    /**
     * Dropped Feedback (The fact could not enter the feedback render stream)
     *
     * The reason explains whether the drop came from local burst policy or the underlying hot stream.
     */
    data class Dropped(
        override val fact: FeedbackFact,
        val reason: DropReason
    ) : FeedbackDeliveryResult {
        override val delivered: Boolean = false
    }
}

/**
 * Feedback Drop Reason (Stable diagnostics for suppressed feedback)
 *
 * Tests assert these values instead of matching log text or relying on SharedFlow implementation details.
 */
enum class DropReason {
    NO_COLLECTOR,
    BURST_LIMIT,
    STREAM_REJECTED
}
