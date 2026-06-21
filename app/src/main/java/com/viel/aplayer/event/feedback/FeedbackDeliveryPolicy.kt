package com.viel.aplayer.event.feedback

/**
 * Owns identity aggregation, severity replacement, provisional hold, and burst limits.
 *
 * The policy is deliberately independent from Android, Flow, and render mode so feedback delivery can be
 * unit-tested through one small interface. It is synchronous: [evaluate] classifies a fact immediately,
 * and for provisional outcomes it parks a pending entry and asks the caller (the event sink) to schedule
 * [releasePending] after the hold. All mutable state is guarded so the synchronous caller thread and the
 * delayed release coroutine cannot interleave.
 */
class FeedbackDeliveryPolicy(
    private val provisionalHoldMs: Long = DEFAULT_PROVISIONAL_HOLD_MS,
    private val finalMergeWindowMs: Long = DEFAULT_FINAL_MERGE_WINDOW_MS,
    private val burstWindowMs: Long = DEFAULT_BURST_WINDOW_MS,
    private val burstLimit: Int = DEFAULT_BURST_LIMIT,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class SlotKey(
        val identity: FeedbackAggregationIdentity,
        val taskInstance: FeedbackTaskInstance
    )

    private data class PendingProvisional(val generation: Long)

    private data class VisibleFinal(val severity: FeedbackSeverity, val emittedAtMs: Long)

    private val lock = Any()
    private val pendingBySlot = linkedMapOf<SlotKey, PendingProvisional>()
    private val visibleFinalBySlot = linkedMapOf<SlotKey, VisibleFinal>()
    private val recentDeliveryTimes = ArrayDeque<Long>()
    private var generationCounter = 0L

    /**
     * Classifies a fact before any stream emission.
     *
     * Final facts that survive identity and severity rules deliver immediately; provisional facts park a
     * pending entry and return [FeedbackDeliveryDecision.Hold] so the sink can schedule the delayed release.
     */
    fun evaluate(fact: FeedbackFact): FeedbackDeliveryDecision = synchronized(lock) {
        val now = nowMs()
        prune(now)
        val key = SlotKey(fact.outcome.identity, fact.outcome.taskInstance)
        when (fact.outcome.lifecycle) {
            FeedbackLifecycle.PROVISIONAL -> evaluateProvisional(key, now)
            FeedbackLifecycle.FINAL -> evaluateFinal(key, fact.outcome.severity, now)
        }
    }

    /**
     * Resolves a held provisional after its hold expired.
     *
     * The sink calls this with the generation handed back by the matching [FeedbackDeliveryDecision.Hold].
     * A provisional that was superseded by a newer provisional or cancelled by a final reports
     * [FeedbackDeliveryDecision.Merged] and is not rendered; the survivor consumes a burst slot and delivers.
     */
    fun releasePending(fact: FeedbackFact, generation: Long): FeedbackDeliveryDecision =
        synchronized(lock) {
            val key = SlotKey(fact.outcome.identity, fact.outcome.taskInstance)
            val pending = pendingBySlot[key] ?: return FeedbackDeliveryDecision.Merged
            if (pending.generation != generation) return FeedbackDeliveryDecision.Merged
            pendingBySlot.remove(key)
            val now = nowMs()
            prune(now)
            if (recentDeliveryTimes.size >= burstLimit) {
                return FeedbackDeliveryDecision.Dropped(DropReason.BURST_LIMIT)
            }
            recentDeliveryTimes.addLast(now)
            FeedbackDeliveryDecision.Deliver
        }

    private fun evaluateProvisional(key: SlotKey, now: Long): FeedbackDeliveryDecision {
        val visible = visibleFinalBySlot[key]
        if (visible != null && now - visible.emittedAtMs <= finalMergeWindowMs) {
            return FeedbackDeliveryDecision.Merged
        }
        val generation = ++generationCounter
        pendingBySlot[key] = PendingProvisional(generation)
        return FeedbackDeliveryDecision.Hold(provisionalHoldMs, generation)
    }

    private fun evaluateFinal(key: SlotKey, severity: FeedbackSeverity, now: Long): FeedbackDeliveryDecision {
        pendingBySlot.remove(key)
        val visible = visibleFinalBySlot[key]
        if (visible != null && now - visible.emittedAtMs <= finalMergeWindowMs &&
            severity.ordinal <= visible.severity.ordinal
        ) {
            return FeedbackDeliveryDecision.Merged
        }
        if (recentDeliveryTimes.size >= burstLimit) {
            return FeedbackDeliveryDecision.Dropped(DropReason.BURST_LIMIT)
        }
        recentDeliveryTimes.addLast(now)
        visibleFinalBySlot[key] = VisibleFinal(severity, now)
        return FeedbackDeliveryDecision.Deliver
    }

    private fun prune(now: Long) {
        while (recentDeliveryTimes.isNotEmpty() && now - recentDeliveryTimes.first() > burstWindowMs) {
            recentDeliveryTimes.removeFirst()
        }
        val expiredFinals = visibleFinalBySlot
            .filterValues { visible -> now - visible.emittedAtMs > finalMergeWindowMs }
            .keys
            .toList()
        expiredFinals.forEach(visibleFinalBySlot::remove)
    }

    companion object {
        const val DEFAULT_PROVISIONAL_HOLD_MS: Long = 350L
        const val DEFAULT_FINAL_MERGE_WINDOW_MS: Long = 1_000L
        const val DEFAULT_BURST_WINDOW_MS: Long = 2_000L
        const val DEFAULT_BURST_LIMIT: Int = 64
    }
}

/**
 * Internal policy result before the hot stream is touched.
 *
 * Separating the policy decision from the final [FeedbackDeliveryResult] keeps stream failures and the
 * collector-presence check in the sink while the policy stays Android-free.
 */
sealed interface FeedbackDeliveryDecision {
    data object Deliver : FeedbackDeliveryDecision
    data object Merged : FeedbackDeliveryDecision
    data class Dropped(val reason: DropReason) : FeedbackDeliveryDecision

    /**
     * A provisional fact is parked pending its hold window.
     *
     * The sink schedules [FeedbackDeliveryPolicy.releasePending] after [holdMs], passing
     * [generation] so a superseded provisional resolves to merged instead of rendering.
     */
    data class Hold(val holdMs: Long, val generation: Long) : FeedbackDeliveryDecision
}
