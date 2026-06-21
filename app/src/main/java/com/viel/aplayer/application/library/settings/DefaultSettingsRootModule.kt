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
 * Adapter from granular root/query gateways to the settings scene.
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
        libraryRootGateway.addLibraryRootAndScheduleSync(uri, USER_TRIGGER)
    }

    override fun addWebDavRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
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
        libraryRootGateway.refreshLibraryRootStatuses()
    }

    override suspend fun inspectManualAbsSync(rootId: String): SettingsAbsSyncInspection {
        val preflight = libraryRootGateway.refreshLibraryRootStatus(rootId)
            ?: return SettingsAbsSyncInspection.MissingRoot
        if (!preflight.isSyncAvailable) {
            return SettingsAbsSyncInspection.Blocked(
                LibraryAccessFeedbackFacts.syncBlocked(
                    rootId = preflight.root.id,
                    detailMessage = buildRootUnavailableSyncMessage(preflight)
                )
            )
        }
        val root = preflight.root
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
        scanScheduler.scheduleLibrarySync(USER_TRIGGER)
    }

    private companion object {
        private const val USER_TRIGGER = "USER"
    }
}
