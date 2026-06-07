package com.viel.aplayer.event.feedback

import com.viel.aplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TransientFeedbackDeliveryPolicyTest {
    @Test
    fun `duplicates inside merge window are merged`() {
        // Merge Window Scenario (Verifies duplicate facts are observable as merged rather than emitted twice)
        // The fixed clock keeps the policy test deterministic and independent from Android or Flow.
        var now = 1_000L
        val policy = TransientFeedbackDeliveryPolicy(
            mergeWindowMs = 500L,
            burstWindowMs = 2_000L,
            burstLimit = 8,
            nowMs = { now }
        )
        val fact = TransientFeedbackFact(FeedbackMessages.playbackBookmarkCreated())

        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(fact))

        now += 200L

        assertEquals(FeedbackDeliveryDecision.Merged, policy.evaluate(fact))
    }

    @Test
    fun `burst limit drops distinct feedback facts`() {
        // Burst Limit Scenario (Verifies overload suppression uses a stable dropped result)
        // Distinct message keys avoid the merge path so the test exercises the local burst policy directly.
        val policy = TransientFeedbackDeliveryPolicy(
            mergeWindowMs = 500L,
            burstWindowMs = 2_000L,
            burstLimit = 1,
            nowMs = { 1_000L }
        )

        val first = TransientFeedbackFact(FeedbackMessages.playbackBookmarkCreated())
        val second = TransientFeedbackFact(
            FeedbackMessage.Resource(R.string.feedback_settings_abs_sync_started)
        )

        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(first))
        assertEquals(
            FeedbackDeliveryDecision.Dropped(DropReason.BURST_LIMIT),
            policy.evaluate(second)
        )
    }
}
