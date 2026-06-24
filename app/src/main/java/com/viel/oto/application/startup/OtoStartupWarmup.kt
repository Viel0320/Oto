package com.viel.oto.application.startup

import com.viel.oto.data.entity.LibraryRootEntity

/**
 * Keep cold-start di resolution behind a narrow interface.
 * The coordinator reads only active ABS roots and freshness state until a stale root proves that deeper ABS work is needed.
 */
internal interface StartupWarmupDependencies {
    /**
     * Return only remote roots eligible for startup progress refresh.
     * Implementations should avoid resolving scan, VFS, cover, or ABS network adapters while reading this persisted root list.
     */
    suspend fun activeAbsRoots(): List<LibraryRootEntity>

    /**
     * Check root-level remote progress cache age.
     * The check should be backed by durable sync-state data so fresh roots do not construct the ABS catalog synchronizer.
     */
    suspend fun isAuthorizedProgressRefreshDue(rootId: String, nowMillis: Long): Boolean

    /**
     * Repair local playback progress after the remote freshness gate.
     * This command stays behind the dependency seam so media recovery objects are not bound during warmup construction.
     */
    suspend fun performColdStartSelfHealing()
}

/**
 * Keeps cold-start background work behind a narrow application seam.
 * The application process owns when warmup runs, while this coordinator owns which domain tasks are safe to trigger at startup.
 */
internal class OtoStartupWarmup(
    private val dependencies: StartupWarmupDependencies,
    private val enqueueAbsRootSync: (rootId: String) -> Unit,
    private val onAbsProgressWarmupFailure: (Throwable) -> Unit = {}
) {
    /**
     * Schedules stale remote refreshes before local progress repair.
     * ABS progress scheduling remains best-effort so local cold-start self-healing still runs when the remote gate is fresh or unavailable.
     */
    suspend fun run(nowMillis: Long = System.currentTimeMillis()) {
        runCatching {
            warmUpAbsProgressIfStale(nowMillis)
        }.onFailure { error ->
            onAbsProgressWarmupFailure(error)
        }
        dependencies.performColdStartSelfHealing()
    }

    /**
     * Avoid remote progress refresh on every process creation.
     * Root-scoped freshness keeps cold start cheap while WorkManager still performs eventual ABS catalog and progress synchronization for stale roots.
     */
    internal suspend fun warmUpAbsProgressIfStale(nowMillis: Long): List<String> {
        val staleRootIds = dependencies.activeAbsRoots()
            .filter { root -> dependencies.isAuthorizedProgressRefreshDue(root.id, nowMillis) }
            .map { root -> root.id }
        staleRootIds.forEach(enqueueAbsRootSync)
        return staleRootIds
    }
}
