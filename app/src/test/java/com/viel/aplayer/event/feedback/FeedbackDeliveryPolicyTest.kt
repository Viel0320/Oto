package com.viel.aplayer.event.feedback

import com.viel.aplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackDeliveryPolicyTest {
    private var now = 1_000L
    private val policy = FeedbackDeliveryPolicy(
        provisionalHoldMs = HOLD,
        finalMergeWindowMs = MERGE,
        burstWindowMs = 2_000L,
        burstLimit = 8,
        nowMs = { now }
    )

    @Test
    fun `final replaces same-identity pending provisional`() {
        val held = policy.evaluate(speedProvisional()) as FeedbackDeliveryDecision.Hold

        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(speedFinal()))

        assertEquals(FeedbackDeliveryDecision.Merged, policy.releasePending(speedProvisional(), held.generation))
    }

    @Test
    fun `long-running provisional is delivered after the hold`() {
        val held = policy.evaluate(speedProvisional()) as FeedbackDeliveryDecision.Hold

        now += HOLD + 1
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.releasePending(speedProvisional(), held.generation))
    }

    @Test
    fun `consecutive provisionals replace pending and only the last survives`() {
        val first = policy.evaluate(speedProvisional()) as FeedbackDeliveryDecision.Hold
        val second = policy.evaluate(speedProvisional()) as FeedbackDeliveryDecision.Hold

        assertEquals(FeedbackDeliveryDecision.Merged, policy.releasePending(speedProvisional(), first.generation))
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.releasePending(speedProvisional(), second.generation))
    }

    @Test
    fun `provisionals spaced beyond the hold each deliver`() {
        val first = policy.evaluate(speedProvisional()) as FeedbackDeliveryDecision.Hold
        now += HOLD + 1
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.releasePending(speedProvisional(), first.generation))

        now += MERGE + 1
        val second = policy.evaluate(speedProvisional()) as FeedbackDeliveryDecision.Hold
        now += HOLD + 1
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.releasePending(speedProvisional(), second.generation))
    }

    @Test
    fun `provisional after a visible final in the window is merged`() {
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(speedFinal()))

        assertEquals(FeedbackDeliveryDecision.Merged, policy.evaluate(speedProvisional()))
    }

    @Test
    fun `different identities do not replace each other`() {
        policy.evaluate(speedProvisional())
        val sleepHeld = policy.evaluate(sleepProvisional())
        assertEquals(true, sleepHeld is FeedbackDeliveryDecision.Hold)
    }

    @Test
    fun `different categories do not replace each other`() {
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(speedFinal()))
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(downloadFinal(FeedbackSeverity.COMPLETED)))
    }

    @Test
    fun `failed final overrides a completed final in the window`() {
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(downloadFinal(FeedbackSeverity.COMPLETED)))
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(downloadFinal(FeedbackSeverity.FAILED)))
    }

    @Test
    fun `completed final does not override a failed final in the window`() {
        assertEquals(FeedbackDeliveryDecision.Deliver, policy.evaluate(downloadFinal(FeedbackSeverity.FAILED)))
        assertEquals(FeedbackDeliveryDecision.Merged, policy.evaluate(downloadFinal(FeedbackSeverity.COMPLETED)))
    }

    @Test
    fun `burst limit drops excess distinct identities`() {
        val tightPolicy = FeedbackDeliveryPolicy(
            provisionalHoldMs = HOLD,
            finalMergeWindowMs = MERGE,
            burstWindowMs = 2_000L,
            burstLimit = 1,
            nowMs = { now }
        )
        assertEquals(FeedbackDeliveryDecision.Deliver, tightPolicy.evaluate(speedFinal()))
        assertEquals(
            FeedbackDeliveryDecision.Dropped(DropReason.BURST_LIMIT),
            tightPolicy.evaluate(downloadFinal(FeedbackSeverity.COMPLETED))
        )
    }

    private fun speedProvisional() = playbackControl(
        FeedbackTopic.PlaybackSpeed,
        FeedbackSeverity.COMPLETED,
        FeedbackLifecycle.PROVISIONAL
    )

    private fun speedFinal() = playbackControl(
        FeedbackTopic.PlaybackSpeed,
        FeedbackSeverity.COMPLETED,
        FeedbackLifecycle.FINAL
    )

    private fun sleepProvisional() = playbackControl(
        FeedbackTopic.SleepTimer,
        FeedbackSeverity.COMPLETED,
        FeedbackLifecycle.PROVISIONAL
    )

    private fun playbackControl(
        topic: FeedbackTopic,
        severity: FeedbackSeverity,
        lifecycle: FeedbackLifecycle
    ) = FeedbackFact(
        message = FeedbackMessages.playbackBookmarkCreated(),
        outcome = FeedbackOutcome(
            identity = FeedbackAggregationIdentity(
                category = FeedbackCategory.PLAYBACK_CONTROL,
                topic = topic,
                context = FeedbackContext.PlaybackControl
            ),
            severity = severity,
            lifecycle = lifecycle
        )
    )

    private fun downloadFinal(severity: FeedbackSeverity) = FeedbackFact(
        message = FeedbackMessage.Resource(R.string.feedback_download_cache_deleted),
        outcome = FeedbackOutcome(
            identity = FeedbackAggregationIdentity(
                category = FeedbackCategory.DOWNLOAD_CACHE,
                topic = FeedbackTopic.DownloadCacheTask,
                context = FeedbackContext.DownloadCacheTask(bookId = "book-1")
            ),
            severity = severity,
            lifecycle = FeedbackLifecycle.FINAL
        )
    )

    companion object {
        private const val HOLD = 350L
        private const val MERGE = 1_000L
    }
}
