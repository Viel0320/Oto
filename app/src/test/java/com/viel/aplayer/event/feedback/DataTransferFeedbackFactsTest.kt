package com.viel.aplayer.event.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the global data-transfer identity and export/import separation.
 *
 * Verifies export and import feedback stay in the data transfer category on the global context while the
 * two user tasks keep separate topics so they never absorb each other.
 */
class DataTransferFeedbackFactsTest {

    @Test
    fun `export feedback is a global data transfer outcome`() {
        val identity = DataTransferFeedbackFacts.exportSucceeded().outcome.identity

        assertEquals(FeedbackCategory.DATA_TRANSFER, identity.category)
        assertEquals(FeedbackTopic.DataExport, identity.topic)
        assertEquals(FeedbackContext.Global, identity.context)
    }

    @Test
    fun `export and import never share an identity`() {
        val export = DataTransferFeedbackFacts.exportSucceeded().outcome.identity
        val import = DataTransferFeedbackFacts.importStreamFailed().outcome.identity

        assertEquals(export.category, import.category)
        assertNotEquals(export, import)
    }

    @Test
    fun `failures classify as failed while export success is completed`() {
        assertEquals(FeedbackSeverity.COMPLETED, DataTransferFeedbackFacts.exportSucceeded().outcome.severity)
        assertEquals(FeedbackSeverity.FAILED, DataTransferFeedbackFacts.exportStreamFailed().outcome.severity)
        assertEquals(FeedbackSeverity.FAILED, DataTransferFeedbackFacts.exportFailed("boom").outcome.severity)
        assertEquals(FeedbackSeverity.FAILED, DataTransferFeedbackFacts.importStreamFailed().outcome.severity)
        assertEquals(
            FeedbackSeverity.FAILED,
            DataTransferFeedbackFacts.importVersionIncompatible(99, 41).outcome.severity
        )
        assertEquals(FeedbackSeverity.FAILED, DataTransferFeedbackFacts.importFailed("boom").outcome.severity)
    }
}
