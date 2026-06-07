package com.viel.aplayer.event.feedback

/**
 * Transient Feedback Delivery Policy (Owns merge and burst suppression rules)
 *
 * The policy is deliberately independent from Android and Flow so dropped and merged outcomes can be
 * unit-tested through one small interface.
 */
class TransientFeedbackDeliveryPolicy(
    private val mergeWindowMs: Long = DEFAULT_MERGE_WINDOW_MS,
    private val burstWindowMs: Long = DEFAULT_BURST_WINDOW_MS,
    private val burstLimit: Int = DEFAULT_BURST_LIMIT,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val lastDeliveredByMergeKey = linkedMapOf<String, Long>()
    private val recentDeliveryTimes = ArrayDeque<Long>()

    /**
     * Evaluate Feedback Fact (Classifies a fact before stream emission)
     *
     * Duplicate feedback inside the merge window is merged; otherwise the burst counter limits how many
     * one-shot messages can be queued in a short interval.
     */
    fun evaluate(fact: TransientFeedbackFact): FeedbackDeliveryDecision {
        val now = nowMs()
        prune(now)
        val lastDeliveredAt = lastDeliveredByMergeKey[fact.mergeKey]
        if (lastDeliveredAt != null && now - lastDeliveredAt <= mergeWindowMs) {
            return FeedbackDeliveryDecision.Merged
        }
        if (recentDeliveryTimes.size >= burstLimit) {
            return FeedbackDeliveryDecision.Dropped(DropReason.BURST_LIMIT)
        }
        lastDeliveredByMergeKey[fact.mergeKey] = now
        recentDeliveryTimes.addLast(now)
        return FeedbackDeliveryDecision.Deliver
    }

    private fun prune(now: Long) {
        while (recentDeliveryTimes.isNotEmpty() && now - recentDeliveryTimes.first() > burstWindowMs) {
            recentDeliveryTimes.removeFirst()
        }
        val expiredKeys = lastDeliveredByMergeKey
            .filterValues { deliveredAt -> now - deliveredAt > mergeWindowMs }
            .keys
            .toList()
        expiredKeys.forEach(lastDeliveredByMergeKey::remove)
    }

    companion object {
        const val DEFAULT_MERGE_WINDOW_MS: Long = 1_000L
        const val DEFAULT_BURST_WINDOW_MS: Long = 2_000L
        const val DEFAULT_BURST_LIMIT: Int = 64
    }
}

/**
 * Feedback Delivery Decision (Internal policy result before the hot stream is touched)
 *
 * Separating the policy decision from the final delivery result keeps stream failures visible without
 * folding them into merge or burst semantics.
 */
sealed interface FeedbackDeliveryDecision {
    data object Deliver : FeedbackDeliveryDecision
    data object Merged : FeedbackDeliveryDecision
    data class Dropped(val reason: DropReason) : FeedbackDeliveryDecision
}
