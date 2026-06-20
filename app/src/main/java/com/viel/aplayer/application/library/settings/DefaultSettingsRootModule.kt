package com.viel.aplayer.application.library.settings

import android.net.Uri
import com.viel.aplayer.abs.sync.AbsSyncPlan
import com.viel.aplayer.abs.sync.AbsSyncTaskOrigin
import com.viel.aplayer.application.usecase.LibraryRootSettingsSnapshot
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.event.feedback.LibraryAccessFeedbackFacts
import com.viel.aplayer.library.availability.buildRootUnavailableSyncMessage
import com.viel.aplayer.library.availability.isSyncAvailable
import kotlinx.coroutines.flow.Flow

/**
 * Default Settings Root Module (Adapter from granular root/query gateways to the settings scene)
 * Centralizes settings root display, registration, status refresh, and scan trigger operations away from the broad library transition entry point.
 */
class DefaultSettingsRootModule(
    private val observeRootSnapshotsSource: () -> Flow<List<LibraryRootSettingsSnapshot>>,
    private val libraryRootGateway: LibraryRootGateway,
    private val scanScheduler: ScanScheduler,
    private val inspectAbsSyncPlan: suspend (LibraryRootEntity) -> AbsSyncPlan,
    private val startAbsSyncTask: (rootId: String, origin: AbsSyncTaskOrigin) -> Boolean
) : SettingsRootReadModel, SettingsRootCommands {

    override fun observeRootSnapshots(): Flow<List<LibraryRootSettingsSnapshot>> {
        return observeRootSnapshotsSource()
    }

    override fun addLocalRootAndScheduleSync(uri: Uri) {
        // Local Root Registration Trigger (Keep settings additions on the user sync path)
        // The root gateway still owns SAF permission persistence, while this module owns the settings-scoped trigger choice.
        libraryRootGateway.addLibraryRootAndScheduleSync(uri, USER_TRIGGER)
    }

    override fun addWebDavRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        // WebDAV Root Registration Trigger (Keep remote additions on the user sync path)
        // The settings-root module passes all connection fields through unchanged and hides the trigger label from UI callers.
        libraryRootGateway.addWebDavLibraryRootAndScheduleSync(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath,
            trigger = USER_TRIGGER
        )
    }

    override suspend fun refreshAllRootStatuses() {
        // Overlay Root Refresh (Refresh all roots from the scene command boundary)
        // SettingsViewModel only reacts to overlay visibility while the module selects the root gateway operation.
        libraryRootGateway.refreshLibraryRootStatuses()
    }

    override suspend fun inspectManualAbsSync(rootId: String): SettingsAbsSyncInspection {
        val preflight = libraryRootGateway.refreshLibraryRootStatus(rootId)
            ?: return SettingsAbsSyncInspection.MissingRoot
        if (!preflight.isSyncAvailable) {
            // Manual ABS Sync Availability Guard (Block plan inspection when the selected root is unreachable)
            // Plan inspection calls the remote Audiobookshelf server, so the refreshed root status must pass before any catalog request leaves the app.
            return SettingsAbsSyncInspection.Blocked(
                LibraryAccessFeedbackFacts.syncBlocked(
                    rootId = preflight.root.id,
                    detailMessage = buildRootUnavailableSyncMessage(preflight)
                )
            )
        }
        val root = preflight.root
        // Manual ABS Sync Plan Projection (Convert ABS plan details into settings-scoped confirmation data)
        // The synchronizer still receives the persisted root entity, while SettingsViewModel only receives rootId, display name, and item count.
        val plan = inspectAbsSyncPlan(root)
        return SettingsAbsSyncInspection.Ready(
            rootId = root.id,
            displayName = root.displayName,
            totalItems = plan.totalItems,
            requiresConfirmation = plan.requiresConfirmation
        )
    }

    override fun startManualAbsSync(rootId: String): Boolean {
        return startAbsSyncTask(rootId, AbsSyncTaskOrigin.MANUAL)
    }

    override fun startAutoAbsSync(rootId: String): Boolean {
        return startAbsSyncTask(rootId, AbsSyncTaskOrigin.AUTO_ADD)
    }

    override fun scheduleUserSync() {
        // Manual Scan Trigger (Queue user-initiated library sync from settings)
        // Keeping the trigger constant here prevents duplicated scheduler strings across presentation callers.
        scanScheduler.scheduleLibrarySync(USER_TRIGGER)
    }

    private companion object {
        private const val USER_TRIGGER = "USER"
    }
}
