package com.viel.oto.application.usecase

import android.net.Uri
import com.viel.oto.data.cache.CacheEvictionCoordinator
import com.viel.oto.library.root.LibraryRootGateway
import com.viel.oto.library.scan.ScanScheduler
import com.viel.oto.library.availability.MissingBookFileRecoveryChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles root edits that need cache eviction and recovery.
 * SettingsViewModel delegates post-edit filesystem repair here, keeping database cache cleanup and recovery checks out of UI state handling.
 */
class SettingsLibraryMaintenanceUseCase(
    private val libraryRootGateway: LibraryRootGateway,
    private val scanScheduler: ScanScheduler,
    private val cacheEvictionCoordinator: CacheEvictionCoordinator,
    private val missingBookFileRecoveryChecker: MissingBookFileRecoveryChecker
) {
    /**
     * Replaces one SAF root and queues only that root for user-priority reconciliation.
     *
     * Cache eviction and missing-file recovery follow the persisted root returned by the gateway, because
     * edit mode can reuse an already registered SAF root when the picker returns the same tree identity.
     */
    suspend fun updateSafRootAndScheduleSync(id: String, newUri: Uri) = withContext(Dispatchers.IO) {
        val updatedRoot = libraryRootGateway.updateSafLibraryRoot(id, newUri)
        clearRootCacheAndRecover(rootId = updatedRoot.id)
        scanScheduler.scheduleLibrarySync("USER", rootIds = setOf(updatedRoot.id))
    }

    /**
     * Replaces one WebDAV root and queues only that root for user-priority reconciliation.
     *
     * The network requirement is preserved for command intent, while the scheduler keeps the actual
     * user scan in its root-scoped priority lane.
     */
    suspend fun updateWebDavRootAndScheduleSync(
        id: String,
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) = withContext(Dispatchers.IO) {
        libraryRootGateway.updateWebDavLibraryRoot(
            id = id,
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
        clearRootCacheAndRecover(rootId = id)
        scanScheduler.scheduleLibrarySync("USER", requiresNetwork = true, rootIds = setOf(id))
    }

    suspend fun clearRootCacheAndRecover(rootId: String) = withContext(Dispatchers.IO) {
        cacheEvictionCoordinator.evictRootCaches(rootId)
        missingBookFileRecoveryChecker.recoverMissingAudioFiles()
    }
}
