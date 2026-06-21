package com.viel.aplayer.library.scan

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.runCatchingCancellable
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import com.viel.aplayer.library.availability.isDirectorySyncRoot
import com.viel.aplayer.library.availability.isSyncAvailable
import com.viel.aplayer.library.orchestrator.RescanType

/**
 * Stable ingestion request shape.
 * Keeps trigger interpretation inside the scan module so UI and Worker callers do not duplicate rescan mode decisions.
 */
data class ScanCommand(
    val trigger: AudiobookSchema.ScanTrigger
)

/**
 * Root preflight seam.
 * Supplies freshly persisted root availability snapshots without exposing LibraryRootStore or Android context to ScanSession.
 */
fun interface ScanRootStatusAdapter {
    suspend fun refreshPermissionStatuses(): List<LibraryRootAvailabilityUpdate>
}

/**
 * Inventory and transaction seam.
 * Runs the heavy directory inventory and import transaction path after ScanSession has selected the allowed directory roots.
 */
fun interface ScanImportAdapter {
    suspend fun rescan(type: RescanType, allowedRootIds: Set<String>): ScanSessionEntity
}

/**
 * Post-import catalog seam.
 * Reads the minimal catalog state needed for outcome mapping without coupling ScanSession to Room DAOs.
 */
fun interface ScanLibrarySnapshotAdapter {
    suspend fun isLibraryEmpty(): Boolean
}

/**
 * Command-level scan state machine.
 * Converts one scan command into one ScanOutcome while infrastructure details stay behind root, import, and snapshot adapters.
 */
class ScanSession(
    private val rootStatusAdapter: ScanRootStatusAdapter,
    private val importAdapter: ScanImportAdapter,
    private val librarySnapshotAdapter: ScanLibrarySnapshotAdapter
) {

    suspend fun execute(command: ScanCommand): ScanOutcome =
        runCatchingCancellable {
            executeChecked(command)
        }.getOrElse { error ->
            ScanOutcomePolicy.fromFailure(error)
        }

    private suspend fun executeChecked(command: ScanCommand): ScanOutcome {
        val rootUpdates = rootStatusAdapter.refreshPermissionStatuses()
        val hasAvailableLibrary = rootUpdates.any { update -> update.isSyncAvailable }
        val unavailableRootUpdates = rootUpdates.filterNot { update -> update.isSyncAvailable }
        val directoryRootUpdates = rootUpdates
            .filter { update -> update.root.isDirectorySyncRoot() }
        val availableDirectoryRootIds = directoryRootUpdates
            .filter { update -> update.isSyncAvailable }
            .map { update -> update.root.id }
            .toSet()

        if (availableDirectoryRootIds.isEmpty()) {
            if (hasAvailableLibrary && unavailableRootUpdates.isEmpty()) {
                return ScanOutcomePolicy.noScanWorkRequired()
            }
            return ScanOutcomePolicy.blocked(
                unavailableRoots = unavailableRootUpdates,
                hasAvailableLibrary = hasAvailableLibrary
            )
        }

        val session = importAdapter.rescan(
            type = command.toRescanType(),
            allowedRootIds = availableDirectoryRootIds
        )
        if (session.status != AudiobookSchema.ScanStatus.COMPLETED) {
            return ScanOutcomePolicy.fromFailure(
                IllegalStateException("Scan session ended as ${session.status}")
            )
        }
        return ScanOutcomePolicy.fromCompletedSession(
            session = session,
            isLibraryEmpty = librarySnapshotAdapter.isLibraryEmpty(),
            skippedRoots = unavailableRootUpdates
        )
    }

    private fun ScanCommand.toRescanType(): RescanType =
        if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
            RescanType.COLD_START_LIGHT
        } else {
            RescanType.USER_GLOBAL
        }
}
