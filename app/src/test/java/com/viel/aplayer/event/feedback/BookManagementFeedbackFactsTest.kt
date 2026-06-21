package com.viel.aplayer.event.feedback

import com.viel.aplayer.R
import com.viel.aplayer.application.library.LibraryReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks per-book identity separation and cover-metadata aggregation.
 *
 * Verifies deletion, read-status, and bookmark outcomes stay keyed to the book so different books never
 * absorb each other, while cover/metadata regeneration shares one global identity that lets the completed
 * final outcome replace the pending started provisional.
 */
class BookManagementFeedbackFactsTest {

    @Test
    fun `book deletion is keyed to the book and reports kept-source copy`() {
        val keptFact = BookManagementFeedbackFacts.bookDeleted("book-1", sourceFileKept = true)
        val removedFact = BookManagementFeedbackFacts.bookDeleted("book-1", sourceFileKept = false)

        assertEquals(
            R.string.feedback_home_book_deleted_source_kept,
            (keptFact.message as FeedbackMessage.Resource).resId
        )
        assertEquals(keptFact.outcome.identity, removedFact.outcome.identity)
        val identity = keptFact.outcome.identity
        assertEquals(FeedbackCategory.BOOK_MANAGEMENT, identity.category)
        assertEquals(FeedbackTopic.BookDeletion, identity.topic)
        assertEquals(FeedbackContext.Book("book-1"), identity.context)
        assertEquals(FeedbackSeverity.COMPLETED, keptFact.outcome.severity)
        assertEquals(FeedbackLifecycle.FINAL, keptFact.outcome.lifecycle)
    }

    @Test
    fun `different books never share a deletion or read-status identity`() {
        val deleteFirst = BookManagementFeedbackFacts.bookDeleted("book-1", sourceFileKept = true).outcome.identity
        val deleteSecond = BookManagementFeedbackFacts.bookDeleted("book-2", sourceFileKept = true).outcome.identity
        assertNotEquals(deleteFirst, deleteSecond)

        val statusFirst = BookManagementFeedbackFacts
            .readStatusChanged("book-1", LibraryReadStatus.FINISHED).outcome.identity
        val statusSecond = BookManagementFeedbackFacts
            .readStatusChanged("book-2", LibraryReadStatus.FINISHED).outcome.identity
        assertNotEquals(statusFirst, statusSecond)
    }

    @Test
    fun `deletion and read-status for the same book do not share identity`() {
        val deletion = BookManagementFeedbackFacts.bookDeleted("book-1", sourceFileKept = true).outcome.identity
        val readStatus = BookManagementFeedbackFacts
            .readStatusChanged("book-1", LibraryReadStatus.IN_PROGRESS).outcome.identity

        assertEquals(deletion.context, readStatus.context)
        assertNotEquals(deletion, readStatus)
    }

    @Test
    fun `cover metadata started and completed share one global identity`() {
        val started = BookManagementFeedbackFacts.coverMetadataRegenerationStarted()
        val completed = BookManagementFeedbackFacts.coverMetadataRegenerationCompleted()

        assertEquals(started.outcome.identity, completed.outcome.identity)
        assertEquals(FeedbackContext.Global, started.outcome.identity.context)
        assertEquals(FeedbackTopic.CoverMetadataRegeneration, started.outcome.identity.topic)
        assertEquals(FeedbackSeverity.STARTED, started.outcome.severity)
        assertEquals(FeedbackLifecycle.PROVISIONAL, started.outcome.lifecycle)
        assertEquals(FeedbackSeverity.COMPLETED, completed.outcome.severity)
        assertEquals(FeedbackLifecycle.FINAL, completed.outcome.lifecycle)
    }

    @Test
    fun `bookmark creation stays a per-book completed outcome`() {
        val fact = BookManagementFeedbackFacts.bookmarkCreated("book-1")

        val identity = fact.outcome.identity
        assertEquals(FeedbackTopic.BookmarkCreation, identity.topic)
        assertEquals(FeedbackContext.Book("book-1"), identity.context)
        assertEquals(FeedbackSeverity.COMPLETED, fact.outcome.severity)
    }
}
