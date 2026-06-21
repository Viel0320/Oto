package com.viel.aplayer.event.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the aggregation identity contract before producers migrate.
 *
 * These tests pin the rules ADR 0001 and CONTEXT.md require: identity is built from typed category,
 * topic, and context only; it never carries localized copy, display names, paths, URLs, or credentials;
 * a missing object is explicit; and different user-meaningful objects stay distinct.
 */
class FeedbackOutcomeTest {

    @Test
    fun `identity equality ignores the renderable message and severity`() {
        val identity = FeedbackAggregationIdentity(
            category = FeedbackCategory.DOWNLOAD_CACHE,
            topic = FeedbackTopic.DownloadCacheTask,
            context = FeedbackContext.DownloadCacheTask(bookId = "book-1")
        )
        val started = FeedbackOutcome(identity, FeedbackSeverity.STARTED, FeedbackLifecycle.PROVISIONAL)
        val completed = FeedbackOutcome(identity, FeedbackSeverity.COMPLETED, FeedbackLifecycle.FINAL)

        assertEquals(started.identity, completed.identity)
        assertNotEquals(started, completed)
    }

    @Test
    fun `context for different books does not collapse into one identity`() {
        val first = FeedbackContext.Book(bookId = "book-1")
        val second = FeedbackContext.Book(bookId = "book-2")

        assertNotEquals(first, second)
    }

    @Test
    fun `missing object context is explicit and distinct from global`() {
        assertNotEquals(FeedbackContext.MissingObject, FeedbackContext.Global)
    }

    @Test
    fun `library root context keeps the access form so peer forms stay distinct`() {
        val webdav = FeedbackContext.LibraryRoot(rootId = "root-1", accessForm = LibraryAccessForm.WEBDAV)
        val abs = FeedbackContext.LibraryRoot(rootId = "root-1", accessForm = LibraryAccessForm.AUDIOBOOKSHELF)

        assertNotEquals(webdav, abs)
    }

    @Test
    fun `identity string form carries no localized copy, display name, path, or url`() {
        val identity = FeedbackAggregationIdentity(
            category = FeedbackCategory.LIBRARY_ACCESS,
            topic = FeedbackTopic.LibrarySync,
            context = FeedbackContext.LibraryRoot(rootId = "root-1", accessForm = LibraryAccessForm.AUDIOBOOKSHELF)
        )

        val rendered = identity.toString()

        assertTrue(rendered.contains("root-1"))
        listOf("http", "https", "://", "Bearer", "/storage/", "content://", "token").forEach { forbidden ->
            assertTrue(
                "identity must not embed sensitive or display fields: $forbidden",
                !rendered.contains(forbidden, ignoreCase = true)
            )
        }
    }

    @Test
    fun `default task instance is single shot`() {
        val outcome = FeedbackOutcome(
            identity = FeedbackAggregationIdentity(
                category = FeedbackCategory.PLAYBACK_CONTROL,
                topic = FeedbackTopic.PlaybackSpeed,
                context = FeedbackContext.PlaybackControl
            ),
            severity = FeedbackSeverity.COMPLETED,
            lifecycle = FeedbackLifecycle.PROVISIONAL
        )

        assertEquals(FeedbackTaskInstance.SingleShot, outcome.taskInstance)
    }
}
