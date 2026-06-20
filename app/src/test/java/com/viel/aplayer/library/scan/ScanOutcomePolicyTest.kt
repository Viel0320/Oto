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

        // Successful Scan Outcome (Maps persisted scan counts into the shared command result)
        // Both manual scans and WorkManager observe this same success category and user-facing message.
        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
        assertEquals(session, outcome.session)
        val feedback = outcome.feedback!!
        val message = feedback.message as FeedbackMessage.Quantity
        assertEquals(R.plurals.feedback_scan_completed_with_discovered_books, message.resId)
        assertEquals(2, message.quantity)
        assertEquals(listOf(2), message.args)
        // A clean scan with no skipped roots stays a global library-access rescan outcome.
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

        // Partial Scan Outcome (Preserves soft failures inside one command result)
        // The policy keeps partial imports and skipped roots together so callers avoid emitting competing scan messages.
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
        // Skipped Root Feedback Mapping (Asserts availability status is resource-backed instead of raw localized text)
        // The root name remains a formatting argument while the TIMEOUT status chooses the stable localized feedback key.
        assertTrue(message.parts.any { part ->
            part is FeedbackMessage.Resource &&
                part.resId == R.string.feedback_sync_root_unavailable_timeout &&
                part.args == listOf("Remote Shelf")
        })
        // Single Skipped Root Identity (One skipped root keys feedback to that stable, non-sensitive root)
        // The display name stays a render argument while the rootId and access form form the identity.
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

        // Completed Session Policy Guard (Rejects sessions that never reached the scanner completion state)
        // This keeps direct policy callers from mapping ABANDONED or RUNNING rows into success or partial command results.
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

        // Blocked Scan Outcome (Represents a valid no-work command instead of a runner failure)
        // No ScanSessionEntity exists because the runner should not start when no usable library is available.
        assertEquals(ScanOutcomeKind.BLOCKED, outcome.kind)
        assertNull(outcome.session)
        val feedback = outcome.feedback!!
        val message = feedback.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_blocked_no_available_libraries, message.resId)
        // Blocked rescan stays a library-access rescan outcome; no skipped root means a global context.
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

        // Failure Classification Outcome (Separates transient infrastructure faults from permanent command failures)
        // WorkManager adapters can use these categories without duplicating exception mapping logic.
        assertEquals(ScanOutcomeKind.RETRY, retry.kind)
        assertEquals(ScanOutcomeKind.FAILED, failed.kind)
        val retryMessage = retry.feedback!!.message as FeedbackMessage.Resource
        val failedMessage = failed.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_retry_later, retryMessage.resId)
        assertEquals(R.string.feedback_scan_failed, failedMessage.resId)
        assertEquals(listOf("bad state"), failedMessage.args)
    }

    // Update ScanOutcomePolicyTest: Change scanSession helper signature to use type-safe AudiobookSchema.ScanStatus enum.
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
