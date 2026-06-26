package com.viel.oto.application.library.settings

import android.net.Uri
import com.viel.oto.abs.sync.AbsSyncPlan
import com.viel.oto.abs.sync.AbsSyncTaskOrigin
import com.viel.oto.application.usecase.LibraryRootSettingsSnapshot
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.root.LibraryRootGateway
import com.viel.oto.library.scan.ScanScheduler
import com.viel.oto.event.feedback.LibraryAccessFeedbackFacts
import com.viel.oto.event.feedback.toRootUnavailableFeedbackMessage
import com.viel.oto.library.availability.isSyncAvailable
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
                    detailMessage = preflight.toRootUnavailableFeedbackMessage()
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

    /**
     * Queues a manual scan for the root selected by the settings action menu.
     *
     * Passing the root id through the application layer prevents a row-level rescan from expanding
     * into an all-root USER scan at the scheduler boundary.
     */
    override fun scheduleUserSync(rootId: String) {
        scanScheduler.scheduleLibrarySync(USER_TRIGGER, rootIds = setOf(rootId))
    }

    private companion object {
        private const val USER_TRIGGER = "USER"
    }
}
