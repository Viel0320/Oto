package com.viel.aplayer.application.usecase

import android.net.Uri
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker
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
    suspend fun updateSafRootAndScheduleSync(id: String, newUri: Uri) = withContext(Dispatchers.IO) {
        libraryRootGateway.updateSafLibraryRoot(id, newUri)
        clearRootCacheAndRecover(rootId = id)
        scanScheduler.scheduleLibrarySync("USER")
    }

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
        scanScheduler.scheduleLibrarySync("USER", requiresNetwork = true)
    }

    suspend fun clearRootCacheAndRecover(rootId: String) = withContext(Dispatchers.IO) {
        cacheEvictionCoordinator.evictRootCaches(rootId)
        missingBookFileRecoveryChecker.recoverMissingAudioFiles()
    }
}
