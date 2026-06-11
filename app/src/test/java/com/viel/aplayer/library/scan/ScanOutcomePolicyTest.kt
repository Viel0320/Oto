package com.viel.aplayer.library.scan

import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.event.feedback.FeedbackMessage
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
        val message = outcome.message as FeedbackMessage.Quantity
        assertEquals(R.plurals.feedback_scan_completed_with_discovered_books, message.resId)
        assertEquals(2, message.quantity)
        assertEquals(listOf(2), message.args)
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
        val message = outcome.message as FeedbackMessage.Composite
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
    fun `blocked scan without reachable roots maps to blocked outcome without session`() {
        val outcome = ScanOutcomePolicy.blocked(emptyList())

        // Blocked Scan Outcome (Represents a valid no-work command instead of a runner failure)
        // No ScanSessionEntity exists because the runner should not start when no directory root is available.
        assertEquals(ScanOutcomeKind.BLOCKED, outcome.kind)
        assertNull(outcome.session)
        val message = outcome.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_blocked_no_directory_roots, message.resId)
    }

    @Test
    fun `io failures request retry while logic failures fail permanently`() {
        val retry = ScanOutcomePolicy.fromFailure(IOException("network down"))
        val failed = ScanOutcomePolicy.fromFailure(IllegalStateException("bad state"))

        // Failure Classification Outcome (Separates transient infrastructure faults from permanent command failures)
        // WorkManager adapters can use these categories without duplicating exception mapping logic.
        assertEquals(ScanOutcomeKind.RETRY, retry.kind)
        assertEquals(ScanOutcomeKind.FAILED, failed.kind)
        val retryMessage = retry.message as FeedbackMessage.Resource
        val failedMessage = failed.message as FeedbackMessage.Resource
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
