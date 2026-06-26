package com.viel.oto.application.startup

import com.viel.oto.data.abs.sync.AbsCatalogStore
import com.viel.oto.abs.sync.isAbsAuthorizedProgressRefreshDue
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.entity.LibraryRootEntity

/**
 * Keeps cold-start freshness reads on persistence-only dependencies.
 * Root and sync-state lookups use DAO providers so constructing the warmup coordinator does not resolve scan scheduling, VFS, cover recovery, or ABS catalog adapters.
 */
class DefaultStartupWarmupDependencies(
    private val libraryRootDaoProvider: () -> LibraryRootDao,
    private val absCatalogStoreProvider: () -> AbsCatalogStore,
    private val coldStartSelfHealing: suspend () -> Unit
) : StartupWarmupDependencies {
    override suspend fun activeAbsRoots(): List<LibraryRootEntity> =
        libraryRootDaoProvider().getActiveAbsRootsOnce()

    override suspend fun isAuthorizedProgressRefreshDue(rootId: String, nowMillis: Long): Boolean {
        val syncState = absCatalogStoreProvider().getSyncState(rootId)
        return isAbsAuthorizedProgressRefreshDue(syncState = syncState, nowMillis = nowMillis)
    }

    override suspend fun performColdStartSelfHealing() {
        coldStartSelfHealing()
    }
}
