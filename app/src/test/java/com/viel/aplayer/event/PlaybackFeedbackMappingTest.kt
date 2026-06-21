package com.viel.aplayer.event

import com.viel.aplayer.R
import com.viel.aplayer.event.feedback.FeedbackCategory
import com.viel.aplayer.event.feedback.FeedbackContext
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.event.feedback.FeedbackRenderMode
import com.viel.aplayer.event.feedback.FeedbackSeverity
import com.viel.aplayer.event.feedback.FeedbackTopic
import com.viel.aplayer.media.PlaybackDomainEvent
import com.viel.aplayer.media.PlaybackSourcePreflightBlockReason
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFeedbackMappingTest {
    @Test
    fun `shutdown scheduled maps to quantity message and playback session shutdown outcome`() {
        val fact = PlaybackDomainEvent.PlaybackFinishedShutdownScheduled(delaySeconds = 5).toFeedbackFact()

        val resource = fact.message as FeedbackMessage.Quantity
        assertEquals(R.plurals.feedback_playback_finished_shutdown_scheduled, resource.resId)
        assertEquals(5, resource.quantity)

        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.PLAYBACK_CONTROL, identity.category)
        assertEquals(FeedbackTopic.PlaybackSessionShutdown, identity.topic)
        assertEquals(FeedbackContext.PlaybackControl, identity.context)
    }

    @Test
    fun `source preflight maps typed block reason to resource message and recovery outcome`() {
        val fact = PlaybackDomainEvent.SourcePreflightBlocked(
            reason = PlaybackSourcePreflightBlockReason.UnavailableRoot,
            rootName = "Shelf"
        ).toFeedbackFact()

        val resource = fact.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_playback_source_preflight_unavailable_root, resource.resId)
        assertEquals(listOf("Shelf"), resource.args)

        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.RECOVERY, identity.category)
        assertEquals(FeedbackTopic.PlaybackSourcePreflight, identity.topic)
        assertEquals(FeedbackContext.MissingObject, identity.context)
        assertEquals(FeedbackSeverity.BLOCKED, fact.outcome.severity)
        assertEquals(FeedbackRenderMode.DIALOG, fact.renderMode)
    }

    @Test
    fun `track unavailable keeps book and queue context for recovery aggregation`() {
        val fact = PlaybackDomainEvent.TrackUnavailable(bookId = "book-7", queueIndex = 3).toFeedbackFact()

        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.RECOVERY, identity.category)
        assertEquals(FeedbackTopic.PlaybackContentAvailability, identity.topic)
        assertEquals(FeedbackContext.PlaybackContent("book-7", 3), identity.context)
        assertEquals(FeedbackSeverity.BLOCKED, fact.outcome.severity)
        assertEquals(FeedbackRenderMode.DIALOG, fact.renderMode)
    }

    @Test
    fun `bookmark created maps to book management outcome keyed by book`() {
        val fact = PlaybackDomainEvent.BookmarkCreated(bookId = "book-9", positionMs = 1000L).toFeedbackFact()

        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.BOOK_MANAGEMENT, identity.category)
        assertEquals(FeedbackTopic.BookmarkCreation, identity.topic)
        assertEquals(FeedbackContext.Book("book-9"), identity.context)
    }

    @Test
    fun `settings root unavailable feedback preserves resource backed detail`() {
        val detail = FeedbackMessage.Resource(
            R.string.feedback_sync_root_unavailable_not_found,
            listOf("Shelf")
        )
        val message = FeedbackMessages.settingsRootUnavailableSyncBlocked(detail)

        val resource = message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_sync_root_unavailable_not_found, resource.resId)
        assertEquals(listOf("Shelf"), resource.args)
    }
}
