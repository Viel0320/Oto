package com.viel.aplayer.event.feedback

import com.viel.aplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the per-book cache outcome contract and identity separation.
 *
 * Verifies queued is a provisional started state keyed to the book, single-book actions stay separate
 * across books, and bulk/permission outcomes never share identity with single-book tasks.
 */
class DownloadCacheFeedbackFactsTest {

    @Test
    fun `queued is a provisional started state keyed to the book`() {
        val fact = DownloadCacheFeedbackFacts.queued("book-1")

        assertEquals(
            R.string.feedback_download_cache_queued,
            (fact.message as FeedbackMessage.Resource).resId
        )
        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.DOWNLOAD_CACHE, identity.category)
        assertEquals(FeedbackTopic.DownloadCacheTask, identity.topic)
        assertEquals(FeedbackContext.DownloadCacheTask("book-1"), identity.context)
        assertEquals(FeedbackSeverity.STARTED, fact.outcome.severity)
        assertEquals(FeedbackLifecycle.PROVISIONAL, fact.outcome.lifecycle)
    }

    @Test
    fun `paused resumed deleted are final completed states for the same book identity`() {
        val queued = DownloadCacheFeedbackFacts.queued("book-1").outcome.identity
        val paused = DownloadCacheFeedbackFacts.paused("book-1")
        val resumed = DownloadCacheFeedbackFacts.resumed("book-1")
        val deleted = DownloadCacheFeedbackFacts.deleted("book-1")

        assertEquals(queued, paused.outcome.identity)
        assertEquals(queued, resumed.outcome.identity)
        assertEquals(queued, deleted.outcome.identity)
        assertEquals(FeedbackSeverity.COMPLETED, paused.outcome.severity)
        assertEquals(FeedbackLifecycle.FINAL, paused.outcome.lifecycle)
        assertEquals(FeedbackLifecycle.FINAL, resumed.outcome.lifecycle)
        assertEquals(FeedbackLifecycle.FINAL, deleted.outcome.lifecycle)
    }

    @Test
    fun `different books never share a download cache identity`() {
        val first = DownloadCacheFeedbackFacts.queued("book-1").outcome.identity
        val second = DownloadCacheFeedbackFacts.queued("book-2").outcome.identity

        assertEquals(first.topic, second.topic)
        assertNotEquals(first, second)
    }

    @Test
    fun `bulk delete does not share identity with a single book delete`() {
        val single = DownloadCacheFeedbackFacts.deleted("book-1").outcome.identity
        val bulk = DownloadCacheFeedbackFacts.deletedAll().outcome.identity

        assertEquals(FeedbackContext.Global, bulk.context)
        assertNotEquals(single, bulk)
    }

    @Test
    fun `notification permission denied is a global hint on its own topic`() {
        val fact = DownloadCacheFeedbackFacts.notificationPermissionDenied()

        val identity = fact.outcome.identity
        assertEquals(FeedbackCategory.DOWNLOAD_CACHE, identity.category)
        assertEquals(FeedbackTopic.DownloadNotificationPermission, identity.topic)
        assertEquals(FeedbackContext.Global, identity.context)
        assertEquals(FeedbackSeverity.HINT, fact.outcome.severity)
        assertNotEquals(
            DownloadCacheFeedbackFacts.queued("book-1").outcome.identity,
            identity
        )
    }

    @Test
    fun `command failure keys to the book when known and global otherwise`() {
        val perBook = DownloadCacheFeedbackFacts.commandFailed("book-1", "redacted")
        val bulk = DownloadCacheFeedbackFacts.commandFailed(null, "redacted")

        assertEquals(FeedbackContext.DownloadCacheTask("book-1"), perBook.outcome.identity.context)
        assertEquals(FeedbackSeverity.FAILED, perBook.outcome.severity)
        assertEquals(FeedbackContext.Global, bulk.outcome.identity.context)
        assertEquals(FeedbackSeverity.FAILED, bulk.outcome.severity)
    }

    @Test
    fun `redacted error stays out of the aggregation identity`() {
        val first = DownloadCacheFeedbackFacts.commandFailed("book-1", "error A")
        val second = DownloadCacheFeedbackFacts.commandFailed("book-1", "error B")

        assertEquals(first.outcome.identity, second.outcome.identity)
        assertTrue(first.message is FeedbackMessage.Resource)
    }
}
