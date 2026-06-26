package com.viel.oto.library.scan

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.entity.ScanSessionEntity
import com.viel.oto.library.availability.AvailabilityResult
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
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
        val notice = outcome.notice!!
        val message = notice.message as ScanNoticeMessage.CompletedWithDiscoveredBooks
        assertEquals(2, message.discoveredCount)
        assertEquals(ScanNoticeContext.Global, notice.context)
        assertEquals(ScanNoticeSeverity.COMPLETED, notice.severity)
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
        val notice = outcome.notice!!
        val message = notice.message as ScanNoticeMessage.Composite
        assertTrue(message.parts.any { part ->
            part is ScanNoticeMessage.CompletedSuffixUpdated &&
                part.updatedCount == 1
        })
        assertTrue(message.parts.any { part ->
            part is ScanNoticeMessage.CompletedSuffixPartial &&
                part.partialCount == 1
        })
        assertTrue(message.parts.any { part ->
            part is ScanNoticeMessage.UnavailableRoot &&
                part.availabilityStatus == AudiobookSchema.AvailabilityStatus.TIMEOUT &&
                part.rootName == "Remote Shelf"
        })
        assertEquals(ScanNoticeContext.LibraryRoot("root-1", ScanNoticeAccessForm.WEBDAV), notice.context)
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
        val notice = outcome.notice!!
        assertEquals(ScanNoticeMessage.BlockedNoAvailableLibraries, notice.message)
        assertEquals(ScanNoticeContext.Global, notice.context)
        assertEquals(ScanNoticeSeverity.BLOCKED, notice.severity)
    }

    @Test
    fun `scan with available non-scanned library reports successful no-work result`() {
        val outcome = ScanOutcomePolicy.noScanWorkRequired()

        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
        val notice = outcome.notice!!
        assertEquals(ScanNoticeMessage.AlreadyUpToDate, notice.message)
        assertEquals(ScanNoticeContext.Global, notice.context)
        assertEquals(ScanNoticeSeverity.COMPLETED, notice.severity)
    }

    @Test
    fun `io failures request retry while logic failures fail permanently`() {
        val retry = ScanOutcomePolicy.fromFailure(IOException("network down"))
        val failed = ScanOutcomePolicy.fromFailure(IllegalStateException("bad state"))

        assertEquals(ScanOutcomeKind.RETRY, retry.kind)
        assertEquals(ScanOutcomeKind.FAILED, failed.kind)
        assertEquals(ScanNoticeMessage.RetryLater, retry.notice!!.message)
        val failedMessage = failed.notice!!.message as ScanNoticeMessage.Failed
        assertEquals("bad state", failedMessage.errorMessage)
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
