package com.viel.aplayer.event.feedback

/**
 * Transient Feedback Fact (Presentation-independent request for short-lived user feedback)
 *
 * The fact carries the message key and delivery policy key, leaving Toast construction and localization
 * to the app shell.
 */
data class TransientFeedbackFact(
    val message: FeedbackMessage,
    val mergeKey: String = message.mergeKey
)
