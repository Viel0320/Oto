package com.viel.oto.event.feedback

import com.viel.oto.R
import com.viel.oto.media.PlaybackSourcePreflightBlockReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the recovery outcome contract and identity safety.
 *
 * Verifies content-availability outcomes keep the user-visible book context while source/security blocks
 * stay on the explicit missing-object context so display names never enter the aggregation identity.
 */
class RecoveryFeedbackFactsTest {

    @Test
    fun `chapter missing keeps book context and recovery identity`() {
        val fact = RecoveryFeedbackFacts.chapterPhysicalFileMissing("book-1")

        assertEquals(
            R.string.feedback_chapter_physical_file_missing,
            (fact.message as FeedbackMessage.Resource).resId
        )
        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.RECOVERY, identity.category)
        assertEquals(FeedbackTopic.PlaybackContentAvailability, identity.topic)
        assertEquals(FeedbackContext.PlaybackContent("book-1"), identity.context)
        assertEquals(FeedbackSeverity.FAILED, fact.outcome.severity)
        assertEquals(FeedbackLifecycle.FINAL, fact.outcome.lifecycle)
    }

    @Test
    fun `different books do not share a chapter-missing identity`() {
        val first = RecoveryFeedbackFacts.chapterPhysicalFileMissing("book-1").outcome.identity
        val second = RecoveryFeedbackFacts.chapterPhysicalFileMissing("book-2").outcome.identity

        assertEquals(first.topic, second.topic)
        assert(first != second)
    }

    @Test
    fun `source preflight block keeps display name out of the identity`() {
        val fact = RecoveryFeedbackFacts.sourcePreflightBlocked(
            reason = PlaybackSourcePreflightBlockReason.UnavailableRoot,
            rootName = "My Shelf"
        )

        assertEquals(FeedbackContext.MissingObject, fact.outcome.identity.context)
        assertEquals(FeedbackSeverity.BLOCKED, fact.outcome.severity)
        assertEquals(FeedbackRenderMode.DIALOG, fact.renderMode)
    }

    @Test
    fun `media recovery blocks render as dialogs`() {
        val facts = listOf(
            RecoveryFeedbackFacts.cleartextPlaybackBlocked(),
            RecoveryFeedbackFacts.initialMediaLoadFailed("boom"),
            RecoveryFeedbackFacts.noAvailableTrackAfterFailure()
        )

        facts.forEach { fact ->
            assertEquals(FeedbackRenderMode.DIALOG, fact.renderMode)
        }
    }

    @Test
    fun `track unavailable carries book queue index and display title`() {
        val fact = RecoveryFeedbackFacts.trackUnavailable("book-3", 2, "Book Three")
        val message = fact.message as FeedbackMessage.PlaybackTrackUnavailable

        assertEquals("book-3", message.bookId)
        assertEquals(2, message.queueIndex)
        assertEquals("Book Three", message.bookTitle)
        assertEquals(FeedbackContext.PlaybackContent("book-3", 2), fact.outcome.identity.context)
        assertEquals(FeedbackSeverity.BLOCKED, fact.outcome.severity)
        assertEquals(FeedbackRenderMode.DIALOG, fact.renderMode)
    }

    @Test
    fun `feedback messages default to toast presentation`() {
        val message = FeedbackMessages.playbackBookmarkCreated()
        val fact = FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.RECOVERY,
                    topic = FeedbackTopic.PlaybackContentAvailability,
                    context = FeedbackContext.MissingObject
                ),
                severity = FeedbackSeverity.COMPLETED,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )

        assertEquals(FeedbackRenderMode.TOAST, message.defaultRenderMode)
        assertEquals(FeedbackRenderMode.TOAST, fact.renderMode)
    }

    @Test
    fun `deleted book recovery keeps the book context and recovery identity`() {
        val ready = RecoveryFeedbackFacts.deletedBookRecoveryRestoredReady("book-4")
        val partial = RecoveryFeedbackFacts.deletedBookRecoveryRestoredPartial("book-4")

        assertEquals(
            R.string.feedback_deleted_book_recovery_restored_ready,
            (ready.message as FeedbackMessage.Resource).resId
        )
        val identity = ready.outcome.identity
        assertEquals(FeedbackCategory.RECOVERY, identity.category)
        assertEquals(FeedbackTopic.DeletedBookRecovery, identity.topic)
        assertEquals(FeedbackContext.Book("book-4"), identity.context)
        assertEquals(FeedbackSeverity.COMPLETED, ready.outcome.severity)
        assertEquals(identity, partial.outcome.identity)
    }

    @Test
    fun `different books never share a deleted book recovery identity`() {
        val first = RecoveryFeedbackFacts.deletedBookRecoveryRestoredReady("book-4").outcome.identity
        val second = RecoveryFeedbackFacts.deletedBookRecoveryRestoredReady("book-5").outcome.identity
        assert(first != second)
    }
}
