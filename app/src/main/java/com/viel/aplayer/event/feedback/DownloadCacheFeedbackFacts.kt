package com.viel.aplayer.event.feedback

/**
 * Command-owner fact factory for download cache task outcomes.
 *
 * Per-book cache actions stay tied to [FeedbackContext.DownloadCacheTask] so queueing, pausing, resuming,
 * deleting, or failing one book never absorbs another book's outcome. The queued outcome is a provisional
 * started state that the delivery policy can collapse into a later final result for the same book. Bulk
 * deletion and the notification permission notice are app-wide, so they use [FeedbackContext.Global] and a
 * distinct topic, keeping them from sharing identity with single-book tasks. Resource keys are unchanged
 * from the previous UI-side messages; failure detail is redacted by the caller before reaching the fact.
 */
object DownloadCacheFeedbackFacts {

    /** A download was accepted for a specific book; a provisional started state. */
    fun queued(bookId: String): FeedbackFact =
        bookTaskFact(
            message = FeedbackMessages.downloadCacheQueued(),
            bookId = bookId,
            severity = FeedbackSeverity.STARTED,
            lifecycle = FeedbackLifecycle.PROVISIONAL
        )

    /** An in-flight download was paused for a specific book. */
    fun paused(bookId: String): FeedbackFact =
        bookTaskFact(
            message = FeedbackMessages.downloadCachePaused(),
            bookId = bookId,
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = FeedbackLifecycle.FINAL
        )

    /** A paused download resumed for a specific book. */
    fun resumed(bookId: String): FeedbackFact =
        bookTaskFact(
            message = FeedbackMessages.downloadCacheResumed(),
            bookId = bookId,
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = FeedbackLifecycle.FINAL
        )

    /** Cached playback data was removed for a specific book. */
    fun deleted(bookId: String): FeedbackFact =
        bookTaskFact(
            message = FeedbackMessages.downloadCacheDeleted(),
            bookId = bookId,
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = FeedbackLifecycle.FINAL
        )

    /**
     * Every manual download was cleared.
     *
     * This is an app-wide cache action, so it stays on [FeedbackContext.Global] and never shares an
     * identity with any single-book delete.
     */
    fun deletedAll(): FeedbackFact =
        globalFact(
            message = FeedbackMessages.downloadCacheDeleted(),
            topic = FeedbackTopic.DownloadCacheTask,
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = FeedbackLifecycle.FINAL
        )

    /**
     * The listener declined the download notification permission.
     *
     * An app-wide informational hint on its own topic so it never absorbs a per-book task outcome.
     */
    fun notificationPermissionDenied(): FeedbackFact =
        globalFact(
            message = FeedbackMessages.downloadNotificationPermissionDenied(),
            topic = FeedbackTopic.DownloadNotificationPermission,
            severity = FeedbackSeverity.HINT,
            lifecycle = FeedbackLifecycle.FINAL
        )

    /**
     * A download cache command failed.
     *
     * A non-null [bookId] keeps the failure on the specific book's task identity; a bulk command failure
     * (no single book) falls back to [FeedbackContext.Global]. The redacted message is a render argument
     * only and never enters the aggregation identity.
     */
    fun commandFailed(bookId: String?, redactedError: String?): FeedbackFact {
        val message = FeedbackMessages.downloadCacheCommandFailed(redactedError)
        val context = if (bookId != null) {
            FeedbackContext.DownloadCacheTask(bookId)
        } else {
            FeedbackContext.Global
        }
        return FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.DOWNLOAD_CACHE,
                    topic = FeedbackTopic.DownloadCacheTask,
                    context = context
                ),
                severity = FeedbackSeverity.FAILED,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )
    }

    private fun bookTaskFact(
        message: FeedbackMessage,
        bookId: String,
        severity: FeedbackSeverity,
        lifecycle: FeedbackLifecycle
    ): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.DOWNLOAD_CACHE,
                    topic = FeedbackTopic.DownloadCacheTask,
                    context = FeedbackContext.DownloadCacheTask(bookId)
                ),
                severity = severity,
                lifecycle = lifecycle
            )
        )

    private fun globalFact(
        message: FeedbackMessage,
        topic: FeedbackTopic,
        severity: FeedbackSeverity,
        lifecycle: FeedbackLifecycle
    ): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.DOWNLOAD_CACHE,
                    topic = topic,
                    context = FeedbackContext.Global
                ),
                severity = severity,
                lifecycle = lifecycle
            )
        )
}
