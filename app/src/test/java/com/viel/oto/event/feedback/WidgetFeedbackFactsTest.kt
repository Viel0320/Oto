package com.viel.oto.event.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the global device-integration identity for home-screen widget pinning.
 *
 * Verifies the unsupported-launcher feedback stays in the device-integration category on the global
 * context as a final failure, so it never absorbs unrelated feedback families.
 */
class WidgetFeedbackFactsTest {

    @Test
    fun `pin unsupported is a global device integration outcome`() {
        val identity = WidgetFeedbackFacts.pinUnsupported().outcome.identity

        assertEquals(FeedbackCategory.DEVICE_INTEGRATION, identity.category)
        assertEquals(FeedbackTopic.HomeScreenWidget, identity.topic)
        assertEquals(FeedbackContext.Global, identity.context)
    }

    @Test
    fun `pin unsupported classifies as a final failure`() {
        val outcome = WidgetFeedbackFacts.pinUnsupported().outcome

        assertEquals(FeedbackSeverity.FAILED, outcome.severity)
        assertEquals(FeedbackLifecycle.FINAL, outcome.lifecycle)
    }
}
