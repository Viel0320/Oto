package com.viel.aplayer.event.feedback

import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the library-access aggregation identity and access-form separation.
 *
 * Verifies the topic follows the user task while the access form and root/draft stay as context, so one
 * access form or root never absorbs another, and the ABS remote-progress failure stays keyed to the book
 * under the sync topic rather than to a server root.
 */
class LibraryAccessFeedbackFactsTest {

    @Test
    fun `connection testing keeps the access form and draft in context`() {
        val webDav = LibraryAccessFeedbackFacts.webDavConnectionSucceeded("draft-1").outcome.identity
        val abs = LibraryAccessFeedbackFacts.absConnectionSucceeded("draft-1", libraryCount = 2).outcome.identity

        assertEquals(FeedbackCategory.LIBRARY_ACCESS, webDav.category)
        assertEquals(FeedbackTopic.ConnectionTesting, webDav.topic)
        assertEquals(
            FeedbackContext.DraftLibraryAccess("draft-1", LibraryAccessForm.WEBDAV),
            webDav.context
        )
        assertEquals(webDav.topic, abs.topic)
        assertNotEquals(webDav, abs)
    }

    @Test
    fun `connection test failure shares its identity with success for the same draft`() {
        val success = LibraryAccessFeedbackFacts.webDavConnectionSucceeded("draft-1").outcome
        val failure = LibraryAccessFeedbackFacts.webDavConnectionFailed("draft-1", "boom").outcome

        assertEquals(success.identity, failure.identity)
        assertEquals(FeedbackSeverity.COMPLETED, success.severity)
        assertEquals(FeedbackSeverity.FAILED, failure.severity)
    }

    @Test
    fun `duplicate root feedback stays in connection testing with blocked severity`() {
        val webDav = LibraryAccessFeedbackFacts.webDavRootAlreadyExists("draft-1")
        val abs = LibraryAccessFeedbackFacts.absRootAlreadyExists("draft-1")

        assertEquals(FeedbackSeverity.BLOCKED, webDav.outcome.severity)
        assertEquals(
            FeedbackContext.DraftLibraryAccess("draft-1", LibraryAccessForm.WEBDAV),
            webDav.outcome.identity.context
        )
        assertEquals(
            R.string.feedback_settings_webdav_root_already_exists,
            (webDav.message as FeedbackMessage.Resource).resId
        )
        assertEquals(FeedbackSeverity.BLOCKED, abs.outcome.severity)
        assertEquals(
            FeedbackContext.DraftLibraryAccess("draft-1", LibraryAccessForm.AudiobookShelf),
            abs.outcome.identity.context
        )
        assertEquals(
            R.string.feedback_settings_abs_root_already_exists,
            (abs.message as FeedbackMessage.Resource).resId
        )
    }

    @Test
    fun `root changes stay keyed to the specific root and access form`() {
        val webDav = LibraryAccessFeedbackFacts.webDavRootUpdated("root-1").outcome.identity
        val local = LibraryAccessFeedbackFacts.localLibraryRelocated("root-1").outcome.identity

        assertEquals(FeedbackTopic.LibraryRootChange, webDav.topic)
        assertEquals(
            FeedbackContext.LibraryRoot("root-1", LibraryAccessForm.WEBDAV),
            webDav.context
        )
        assertNotEquals(webDav, local)
    }

    @Test
    fun `root removed maps source type to the user-visible access form`() {
        val identity = LibraryAccessFeedbackFacts.rootRemoved(
            rootId = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            playbackWasStopped = true
        ).outcome.identity

        assertEquals(
            FeedbackContext.LibraryRoot("root-1", LibraryAccessForm.AudiobookShelf),
            identity.context
        )
    }

    @Test
    fun `sync feedback for different roots never shares an identity`() {
        val first = LibraryAccessFeedbackFacts.syncCompleted("root-1", addedBooks = 1, failedItems = 0).outcome.identity
        val second = LibraryAccessFeedbackFacts.syncCompleted("root-2", addedBooks = 1, failedItems = 0).outcome.identity

        assertEquals(first.topic, second.topic)
        assertNotEquals(first, second)
    }

    @Test
    fun `remote progress failure is a library sync outcome keyed to the book`() {
        val fact = LibraryAccessFeedbackFacts.remoteProgressSaveFailed("book-7", "Bearer <redacted>")

        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.LIBRARY_ACCESS, identity.category)
        assertEquals(FeedbackTopic.LibrarySync, identity.topic)
        assertEquals(FeedbackContext.Book("book-7"), identity.context)
        assertEquals(FeedbackSeverity.FAILED, fact.outcome.severity)
    }

    @Test
    fun `remote progress failure stays separate from root-bound background sync`() {
        val bookKeyed = LibraryAccessFeedbackFacts.remoteProgressSaveFailed("book-7", null).outcome.identity
        val rootKeyed = LibraryAccessFeedbackFacts.syncFailed("root-1", "boom").outcome.identity

        assertEquals(bookKeyed.topic, rootKeyed.topic)
        assertNotEquals(bookKeyed, rootKeyed)
    }
}
