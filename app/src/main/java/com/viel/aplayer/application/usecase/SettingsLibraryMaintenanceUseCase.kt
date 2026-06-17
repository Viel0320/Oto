package com.viel.aplayer.application.usecase

import android.net.Uri
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings Library Maintenance Use Case (Handles root edits that need cache eviction and recovery)
 * SettingsViewModel delegates post-edit filesystem repair here, keeping database cache cleanup and recovery checks out of UI state handling.
 */
class SettingsLibraryMaintenanceUseCase(
    private val libraryRootGateway: LibraryRootGateway,
    private val scanScheduler: ScanScheduler,
    private val cacheEvictionCoordinator: CacheEvictionCoordinator,
    private val missingBookFileRecoveryChecker: MissingBookFileRecoveryChecker
) {
    suspend fun updateSafRootAndScheduleSync(id: String, newUri: Uri) = withContext(Dispatchers.IO) {
        // Settings Root Update Gateway Use (Target only the library-root seam)
        // This use case edits root coordinates without depending on a broad UI-facing library facade.
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
        // Settings WebDAV Root Update Gateway Use (Target only the library-root seam)
        // The use case owns cache/recovery follow-up while root persistence stays behind LibraryRootGateway.
        libraryRootGateway.updateWebDavLibraryRoot(
            id = id,
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
        clearRootCacheAndRecover(rootId = id)
        // WebDAV Root Edit Rescan (Require connectivity for remote-only root mutations)
        // Updated WebDAV coordinates cannot be validated or traversed offline, so WorkManager should wait for a connected network before executing this queued scan.
        scanScheduler.scheduleLibrarySync("USER", requiresNetwork = true)
    }

    suspend fun clearRootCacheAndRecover(rootId: String) = withContext(Dispatchers.IO) {
        // Root Edit Cache Eviction (Forces scanner and availability checks to re-read the relocated source)
        // The shared coordinator clears directory rows, directory-child rows, artwork, and VFS range blocks so edited roots cannot reuse stale provider snapshots.
        cacheEvictionCoordinator.evictRootCaches(rootId)
        missingBookFileRecoveryChecker.recoverMissingAudioFiles()
    }
}
