package com.viel.aplayer.event.feedback

import com.viel.aplayer.media.PlaybackSourcePreflightBlockReason

/**
 * Recovery Feedback Facts (Fact factory for playback content/source recovery outcomes)
 *
 * Recovery feedback reports current playback content or its source becoming unavailable, and APlayer
 * reporting or attempting a recovery path. Content-availability outcomes keep the user-visible book (and
 * track when known) as context; source/security blocks use [FeedbackContext.MissingObject] because the
 * existing domain events carry only a display name, which must never enter the aggregation identity.
 * Resource keys are unchanged from the previous bridge messages.
 */
object RecoveryFeedbackFacts {

    /** Source Preflight Blocked (Playback plan rejected because its source root is inactive). */
    fun sourcePreflightBlocked(
        reason: PlaybackSourcePreflightBlockReason,
        rootName: String?,
        bookTitle: String? = null
    ): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.playbackSourcePreflightBlocked(reason, rootName, bookTitle),
            topic = FeedbackTopic.PlaybackSourcePreflight,
            context = FeedbackContext.MissingObject,
            severity = FeedbackSeverity.BLOCKED,
            renderMode = FeedbackRenderMode.DIALOG
        )

    /** Cleartext Playback Blocked (HTTP playback rejected by the user's security preference). */
    fun cleartextPlaybackBlocked(bookTitle: String? = null): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.playbackCleartextBlocked(bookTitle),
            topic = FeedbackTopic.PlaybackSourcePreflight,
            context = FeedbackContext.MissingObject,
            severity = FeedbackSeverity.BLOCKED,
            renderMode = FeedbackRenderMode.DIALOG
        )

    /** Initial Media Load Failed (The selected media item failed before producing playback). */
    fun initialMediaLoadFailed(errorMessage: String, bookTitle: String? = null): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.playbackInitialMediaLoadFailed(errorMessage, bookTitle),
            topic = FeedbackTopic.PlaybackContentAvailability,
            context = FeedbackContext.MissingObject,
            severity = FeedbackSeverity.FAILED,
            renderMode = FeedbackRenderMode.DIALOG
        )

    /** No Available Track After Failure (Self-healing could not find a later playable queue item). */
    fun noAvailableTrackAfterFailure(bookTitle: String? = null): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.playbackNoAvailableTrackAfterFailure(bookTitle),
            topic = FeedbackTopic.PlaybackContentAvailability,
            context = FeedbackContext.MissingObject,
            severity = FeedbackSeverity.FAILED,
            renderMode = FeedbackRenderMode.DIALOG
        )

    /** Track Unavailable (Current queue item failed reachability checks during runtime playback). */
    fun trackUnavailable(bookId: String, queueIndex: Int, bookTitle: String? = null): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.playbackTrackUnavailable(bookId, queueIndex, bookTitle),
            topic = FeedbackTopic.PlaybackContentAvailability,
            context = FeedbackContext.PlaybackContent(bookId, queueIndex),
            severity = FeedbackSeverity.BLOCKED,
            renderMode = FeedbackRenderMode.DIALOG
        )

    /** Chapter Physical File Missing (A chapter's backing file is gone when the listener taps it). */
    fun chapterPhysicalFileMissing(bookId: String): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.chapterPhysicalFileMissing(),
            topic = FeedbackTopic.PlaybackContentAvailability,
            context = FeedbackContext.PlaybackContent(bookId),
            severity = FeedbackSeverity.FAILED
        )

    /**
     * Deleted Book Recovery Restored Ready (A soft-deleted book was fully restored and is playable)
     *
     * Keyed to the recovered book so restoring different books never absorbs another's outcome.
     */
    fun deletedBookRecoveryRestoredReady(bookId: String): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.deletedBookRecoveryRestoredReady(),
            topic = FeedbackTopic.DeletedBookRecovery,
            context = FeedbackContext.Book(bookId),
            severity = FeedbackSeverity.COMPLETED
        )

    /** Deleted Book Recovery Restored Partial (A book was restored without some unavailable files). */
    fun deletedBookRecoveryRestoredPartial(bookId: String): FeedbackFact =
        recoveryFact(
            message = FeedbackMessages.deletedBookRecoveryRestoredPartial(),
            topic = FeedbackTopic.DeletedBookRecovery,
            context = FeedbackContext.Book(bookId),
            severity = FeedbackSeverity.COMPLETED
        )

    private fun recoveryFact(
        message: FeedbackMessage,
        topic: FeedbackTopic,
        context: FeedbackContext,
        severity: FeedbackSeverity,
        renderMode: FeedbackRenderMode = FeedbackRenderMode.TOAST
    ): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = recoveryOutcome(topic, context, severity),
            renderMode = renderMode
        )

    /**
     * Recovery Outcome (Builds the shared recovery classification for recovery facts)
     *
     * Keeping the outcome construction shared ensures each recovery condition keeps one aggregation
     * identity while the fact presentation decides whether the shell renders a Toast or Dialog.
     */
    private fun recoveryOutcome(
        topic: FeedbackTopic,
        context: FeedbackContext,
        severity: FeedbackSeverity
    ): FeedbackOutcome =
        FeedbackOutcome(
            identity = FeedbackAggregationIdentity(
                category = FeedbackCategory.RECOVERY,
                topic = topic,
                context = context
            ),
            severity = severity,
            lifecycle = FeedbackLifecycle.FINAL
        )
}
