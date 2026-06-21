package com.viel.aplayer.library.scan

import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.event.feedback.FeedbackCategory
import com.viel.aplayer.event.feedback.FeedbackContext
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackSeverity
import com.viel.aplayer.event.feedback.FeedbackTopic
import com.viel.aplayer.event.feedback.LibraryAccessForm
import com.viel.aplayer.library.availability.AvailabilityResult
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScanOutcomePolicyTest {

    @Test
    fun `completed session with discovered books maps to success outcome`() {
        val session = scanSession(discovered = 2)

        val outcome = ScanOutcomePolicy.fromCompletedSession(
            session = session,
            isLibraryEmpty = false
        )

        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
        assertEquals(session, outcome.session)
        val feedback = outcome.feedback!!
        val message = feedback.message as FeedbackMessage.Quantity
        assertEquals(R.plurals.feedback_scan_completed_with_discovered_books, message.resId)
        assertEquals(2, message.quantity)
        assertEquals(listOf(2), message.args)
        val identity = feedback.outcome.identity
        assertEquals(FeedbackCategory.LIBRARY_ACCESS, identity.category)
        assertEquals(FeedbackTopic.Rescan, identity.topic)
        assertEquals(FeedbackContext.Global, identity.context)
    }

    @Test
    fun `partial imports or skipped roots map to partial outcome`() {
        val session = scanSession(updated = 1, partial = 1)
        val skippedRoot = LibraryRootAvailabilityUpdate(
            root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                sourceUri = "https://example.com/dav",
                displayName = "Remote Shelf"
            ),
            availability = AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.TIMEOUT)
        )

        val outcome = ScanOutcomePolicy.fromCompletedSession(
            session = session,
            isLibraryEmpty = false,
            skippedRoots = listOf(skippedRoot)
        )

        assertEquals(ScanOutcomeKind.PARTIAL, outcome.kind)
        val feedback = outcome.feedback!!
        val message = feedback.message as FeedbackMessage.Composite
        assertTrue(message.parts.any { part ->
            part is FeedbackMessage.Quantity &&
                part.resId == R.plurals.feedback_scan_suffix_updated &&
                part.quantity == 1 &&
                part.args == listOf(1)
        })
        assertTrue(message.parts.any { part ->
            part is FeedbackMessage.Quantity &&
                part.resId == R.plurals.feedback_scan_suffix_partial &&
                part.quantity == 1 &&
                part.args == listOf(1)
        })
        assertTrue(message.parts.any { part ->
            part is FeedbackMessage.Resource &&
                part.resId == R.string.feedback_sync_root_unavailable_timeout &&
                part.args == listOf("Remote Shelf")
        })
        val identity = feedback.outcome.identity
        assertEquals(FeedbackCategory.LIBRARY_ACCESS, identity.category)
        assertEquals(FeedbackTopic.Rescan, identity.topic)
        assertEquals(FeedbackContext.LibraryRoot("root-1", LibraryAccessForm.WEBDAV), identity.context)
    }

    @Test
    fun `non completed session should map to failure outcome`() {
        val session = scanSession(status = AudiobookSchema.ScanStatus.ABANDONED)

        val outcome = ScanOutcomePolicy.fromCompletedSession(
            session = session,
            isLibraryEmpty = false
        )

        assertEquals(ScanOutcomeKind.FAILED, outcome.kind)
        assertNull(outcome.session)
        assertTrue(outcome.cause is IllegalStateException)
    }

    @Test
    fun `blocked scan without any available library maps to neutral no-library feedback`() {
        val outcome = ScanOutcomePolicy.blocked(
            unavailableRoots = emptyList(),
            hasAvailableLibrary = false
        )

        assertEquals(ScanOutcomeKind.BLOCKED, outcome.kind)
        assertNull(outcome.session)
        val feedback = outcome.feedback!!
        val message = feedback.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_blocked_no_available_libraries, message.resId)
        val identity = feedback.outcome.identity
        assertEquals(FeedbackCategory.LIBRARY_ACCESS, identity.category)
        assertEquals(FeedbackTopic.Rescan, identity.topic)
        assertEquals(FeedbackContext.Global, identity.context)
        assertEquals(FeedbackSeverity.BLOCKED, feedback.outcome.severity)
    }

    @Test
    fun `scan with available non-scanned library reports successful no-work result`() {
        val outcome = ScanOutcomePolicy.noScanWorkRequired()

        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
        val feedback = outcome.feedback!!
        val message = feedback.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_already_up_to_date, message.resId)
        assertEquals(FeedbackContext.Global, feedback.outcome.identity.context)
        assertEquals(FeedbackSeverity.COMPLETED, feedback.outcome.severity)
    }

    @Test
    fun `io failures request retry while logic failures fail permanently`() {
        val retry = ScanOutcomePolicy.fromFailure(IOException("network down"))
        val failed = ScanOutcomePolicy.fromFailure(IllegalStateException("bad state"))

        assertEquals(ScanOutcomeKind.RETRY, retry.kind)
        assertEquals(ScanOutcomeKind.FAILED, failed.kind)
        val retryMessage = retry.feedback!!.message as FeedbackMessage.Resource
        val failedMessage = failed.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_retry_later, retryMessage.resId)
        assertEquals(R.string.feedback_scan_failed, failedMessage.resId)
        assertEquals(listOf("bad state"), failedMessage.args)
    }

    private fun scanSession(
        discovered: Int = 0,
        unavailable: Int = 0,
        partial: Int = 0,
        updated: Int = 0,
        status: AudiobookSchema.ScanStatus = AudiobookSchema.ScanStatus.COMPLETED
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = "scan-1",
            trigger = AudiobookSchema.ScanTrigger.USER,
            status = status,
            discoveredBookCount = discovered,
            unavailableBookCount = unavailable,
            partialBookCount = partial,
            updatedBookCount = updated
        )
}
