package com.viel.aplayer.event.feedback

/**
 * Feedback Render Mode (Mutually exclusive app-shell consumption mode)
 *
 * A feedback message is consumed exactly once by the app shell: either as a transient Toast or as a
 * blocking in-app Dialog. The render mode changes the consumer only; it does not create a second
 * message source.
 */
enum class FeedbackRenderMode {
    TOAST,
    DIALOG
}

/**
 * Feedback Fact (Shared request for app-shell feedback rendering)
 *
 * The fact pairs one renderable [message] with a typed [outcome] and one mutually exclusive
 * [renderMode]. Delivery policy is render-independent: it decides aggregation, severity replacement, and
 * provisional holding before the app shell consumes the surviving fact as either Toast or Dialog.
 */
data class FeedbackFact(
    val message: FeedbackMessage,
    val outcome: FeedbackOutcome,
    val renderMode: FeedbackRenderMode = message.defaultRenderMode
)
