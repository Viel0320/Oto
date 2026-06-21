package com.viel.aplayer.application.startup

import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.abs.sync.isAbsAuthorizedProgressRefreshDue
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.LibraryRootEntity

/**
 * Keeps cold-start freshness reads on persistence-only dependencies.
 * Root and sync-state lookups use DAO providers so constructing the warmup coordinator does not resolve scan scheduling, VFS, cover recovery, or ABS catalog adapters.
 */
internal class DefaultStartupWarmupDependencies(
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
