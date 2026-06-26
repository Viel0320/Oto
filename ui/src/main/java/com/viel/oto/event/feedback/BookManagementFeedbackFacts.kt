package com.viel.oto.event.feedback

import com.viel.oto.application.library.LibraryReadStatus

/**
 * Command-owner fact factory for per-book management outcomes.
 *
 * Book management feedback stays tied to the specific [FeedbackContext.Book] so the same action on
 * different books never absorbs another book's outcome. Cover and metadata regeneration is the one
 * app-wide maintenance task here, so it uses [FeedbackContext.Global] and lets its completed final
 * outcome replace the pending started provisional. Resource keys are unchanged from the previous
 * UI-side messages.
 */
object BookManagementFeedbackFacts {

    /**
     * A bookmark was persisted for a specific book.
     *
     * Both the dialog UI path and the media-session domain path converge on this identity so a near
     * simultaneous double trigger collapses to one toast instead of two.
     */
    fun bookmarkCreated(bookId: String): FeedbackFact =
        bookFact(
            message = FeedbackMessages.playbackBookmarkCreated(),
            bookId = bookId,
            topic = FeedbackTopic.BookmarkCreation,
            severity = FeedbackSeverity.COMPLETED
        )

    /**
     * A book was removed from the library.
     *
     * The copy distinguishes whether the source file was kept, but the deletion outcome stays keyed to
     * the book so deletions of different books never absorb each other.
     */
    fun bookDeleted(bookId: String, sourceFileKept: Boolean): FeedbackFact =
        bookFact(
            message = FeedbackMessages.homeBookDeleted(sourceFileKept = sourceFileKept),
            bookId = bookId,
            topic = FeedbackTopic.BookDeletion,
            severity = FeedbackSeverity.COMPLETED
        )

    /** The listener changed a book's read status. */
    fun readStatusChanged(bookId: String, readStatus: LibraryReadStatus): FeedbackFact =
        bookFact(
            message = FeedbackMessages.homeReadStatusUpdated(readStatus),
            bookId = bookId,
            topic = FeedbackTopic.ReadStatusChange,
            severity = FeedbackSeverity.COMPLETED
        )

    /**
     * Cover and metadata rebuild began.
     *
     * App-wide maintenance, so it stays on [FeedbackContext.Global] as a provisional started outcome that
     * the completed final outcome replaces if the rebuild finishes within the hold window.
     */
    fun coverMetadataRegenerationStarted(): FeedbackFact =
        coverMetadataFact(
            message = FeedbackMessages.homeCoverMetadataRegenerationStarted(),
            severity = FeedbackSeverity.STARTED,
            lifecycle = FeedbackLifecycle.PROVISIONAL
        )

    /** Cover and metadata rebuild finished. */
    fun coverMetadataRegenerationCompleted(): FeedbackFact =
        coverMetadataFact(
            message = FeedbackMessages.homeCoverMetadataRegenerationCompleted(),
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = FeedbackLifecycle.FINAL
        )

    private fun bookFact(
        message: FeedbackMessage,
        bookId: String,
        topic: FeedbackTopic,
        severity: FeedbackSeverity
    ): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.BOOK_MANAGEMENT,
                    topic = topic,
                    context = FeedbackContext.Book(bookId)
                ),
                severity = severity,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )

    private fun coverMetadataFact(
        message: FeedbackMessage,
        severity: FeedbackSeverity,
        lifecycle: FeedbackLifecycle
    ): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.BOOK_MANAGEMENT,
                    topic = FeedbackTopic.CoverMetadataRegeneration,
                    context = FeedbackContext.Global
                ),
                severity = severity,
                lifecycle = lifecycle
            )
        )
}
