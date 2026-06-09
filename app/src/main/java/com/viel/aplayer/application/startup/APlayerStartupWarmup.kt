package com.viel.aplayer.application.startup

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity

/**
 * Startup Warmup Coordinator (Keeps cold-start background work behind a narrow application seam)
 * The application process owns when warmup runs, while this coordinator owns which domain tasks are safe to trigger at startup.
 */
class APlayerStartupWarmup(
    private val getAllRootsOnce: suspend () -> List<LibraryRootEntity>,
    private val isAuthorizedProgressRefreshDue: suspend (rootId: String, nowMillis: Long) -> Boolean,
    private val enqueueAbsRootSync: (rootId: String) -> Unit,
    private val performColdStartSelfHealing: suspend () -> Unit,
    private val onAbsProgressWarmupFailure: (Throwable) -> Unit = {}
) {
    /**
     * Run Startup Warmup (Schedules stale remote refreshes before local progress repair)
     * ABS progress scheduling remains best-effort so local cold-start self-healing still runs when the remote gate is fresh or unavailable.
     */
    suspend fun run(nowMillis: Long = System.currentTimeMillis()) {
        runCatching {
            warmUpAbsProgressIfStale(nowMillis)
        }.onFailure { error ->
            onAbsProgressWarmupFailure(error)
        }
        performColdStartSelfHealing()
    }

    /**
     * Startup ABS Progress Freshness Gate (Avoid remote progress refresh on every process creation)
     * Root-scoped freshness keeps cold start cheap while WorkManager still performs eventual ABS catalog and progress synchronization for stale roots.
     */
    internal suspend fun warmUpAbsProgressIfStale(nowMillis: Long): List<String> {
        val staleRootIds = getAllRootsOnce()
            .filter { root -> root.status == AudiobookSchema.LibraryRootStatus.ACTIVE }
            .filter { root -> root.sourceType == AudiobookSchema.LibrarySourceType.ABS }
            .filter { root -> isAuthorizedProgressRefreshDue(root.id, nowMillis) }
            .map { root -> root.id }
        staleRootIds.forEach(enqueueAbsRootSync)
        return staleRootIds
    }
}
