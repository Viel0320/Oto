package com.viel.aplayer.event.feedback

/**
 * Feedback Category (Top-level grouping for short-lived feedback by perceived task and outcome)
 *
 * Categories never absorb each other, even under app-wide burst pressure. Local folders, WebDAV, and
 * Audiobookshelf are peer access forms within [LIBRARY_ACCESS] rather than separate top-level families.
 */
enum class FeedbackCategory {
    LIBRARY_ACCESS,
    PLAYBACK_CONTROL,
    BOOK_MANAGEMENT,
    DOWNLOAD_CACHE,
    RECOVERY,
    DATA_TRANSFER
}

/**
 * Feedback Severity (User-perceived importance used for in-identity replacement)
 *
 * Blocking or failed outcomes outrank completed outcomes, completed outcomes outrank started or queued
 * outcomes, and started or queued outcomes outrank ordinary hints. Severity orders replacement only
 * within the same aggregation identity; it is not a cross-category priority.
 */
enum class FeedbackSeverity {
    HINT,
    STARTED,
    COMPLETED,
    FAILED,
    BLOCKED
}

/**
 * Feedback Lifecycle (Whether an outcome is final or a provisional started/queued state)
 *
 * A [PROVISIONAL] outcome is useful only while the listener waits for the same task to continue. The
 * delivery policy may briefly hold it so a quick task shows only its final result.
 */
enum class FeedbackLifecycle {
    FINAL,
    PROVISIONAL
}

/**
 * Feedback Aggregation Identity (Decides which outcomes can replace each other)
 *
 * The identity combines category, topic, and user-meaningful context. The context refines the identity
 * without replacing the topic, and a missing object is represented explicitly rather than as a blank
 * identity. Identity must never carry localized text, display names, paths, URLs, or credentials.
 */
data class FeedbackAggregationIdentity(
    val category: FeedbackCategory,
    val topic: FeedbackTopic,
    val context: FeedbackContext
)

/**
 * Feedback Outcome (User-visible result the delivery policy classifies)
 *
 * The outcome describes the result the listener understands, separate from the renderable message. The
 * delivery policy reads only this descriptor to aggregate, replace by severity, hold provisional
 * outcomes, and apply burst limits.
 */
data class FeedbackOutcome(
    val identity: FeedbackAggregationIdentity,
    val severity: FeedbackSeverity,
    val lifecycle: FeedbackLifecycle,
    val taskInstance: FeedbackTaskInstance = FeedbackTaskInstance.SingleShot
)
