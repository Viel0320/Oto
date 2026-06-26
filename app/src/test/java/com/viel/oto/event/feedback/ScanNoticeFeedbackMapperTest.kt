package com.viel.oto.event.feedback

import com.viel.oto.R
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.library.scan.ScanNotice
import com.viel.oto.library.scan.ScanNoticeAccessForm
import com.viel.oto.library.scan.ScanNoticeContext
import com.viel.oto.library.scan.ScanNoticeMessage
import com.viel.oto.library.scan.ScanNoticeSeverity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the app-shell mapping from library scan notices to resource-backed feedback facts.
 */
class ScanNoticeFeedbackMapperTest {
    @Test
    fun `completed scan notice maps to rescan feedback identity and discovered count resource`() {
        val fact = ScanNotice(
            message = ScanNoticeMessage.CompletedWithDiscoveredBooks(2),
            context = ScanNoticeContext.Global,
            severity = ScanNoticeSeverity.COMPLETED
        ).toFeedbackFact()

        val message = fact.message as FeedbackMessage.Quantity
        assertEquals(R.plurals.feedback_scan_completed_with_discovered_books, message.resId)
        assertEquals(2, message.quantity)
        assertEquals(FeedbackCategory.LIBRARY_ACCESS, fact.outcome.identity.category)
        assertEquals(FeedbackTopic.Rescan, fact.outcome.identity.topic)
        assertEquals(FeedbackContext.Global, fact.outcome.identity.context)
        assertEquals(FeedbackSeverity.COMPLETED, fact.outcome.severity)
    }

    @Test
    fun `blocked root notice maps to root unavailable resource and root feedback context`() {
        val fact = ScanNotice(
            message = ScanNoticeMessage.UnavailableRoot(
                rootName = "Remote Shelf",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                fallbackCode = "TIMEOUT"
            ),
            context = ScanNoticeContext.LibraryRoot("root-1", ScanNoticeAccessForm.WEBDAV),
            severity = ScanNoticeSeverity.BLOCKED
        ).toFeedbackFact()

        val message = fact.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_sync_root_unavailable_timeout, message.resId)
        assertEquals(listOf("Remote Shelf"), message.args)
        assertEquals(
            FeedbackContext.LibraryRoot("root-1", LibraryAccessForm.WEBDAV),
            fact.outcome.identity.context
        )
        assertEquals(FeedbackSeverity.BLOCKED, fact.outcome.severity)
    }
}
