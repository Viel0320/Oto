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
 *
 * User-initiated commands must carry explicit root ids so the scan state machine can keep manual
 * registration and rescan work scoped to the source the user acted on. Cold-start commands keep an
 * empty target set because they are the only library-wide directory scan entry.
 */
data class ScanCommand(
    val trigger: AudiobookSchema.ScanTrigger,
    val targetRootIds: Set<String> = emptySet()
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
    suspend fun rescan(
        type: RescanType,
        targetRootIds: Set<String>,
        allowedRootIds: Set<String>
    ): ScanSessionEntity
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
        val scopedRootUpdates = command.scopedRootUpdates(rootUpdates)
        val hasAvailableLibrary = scopedRootUpdates.any { update -> update.isSyncAvailable }
        val unavailableRootUpdates = scopedRootUpdates.filterNot { update -> update.isSyncAvailable }
        val directoryRootUpdates = scopedRootUpdates
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
            targetRootIds = command.targetRootIds,
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
        when (trigger) {
            AudiobookSchema.ScanTrigger.COLD_START -> RescanType.COLD_START_LIGHT
            AudiobookSchema.ScanTrigger.ADD_LIBRARY_ROOT -> RescanType.NEW_LIBRARY_ROOT
            AudiobookSchema.ScanTrigger.USER -> RescanType.USER_ROOTS
        }

    /**
     * Narrows availability preflight to the requested roots for user commands.
     *
     * The empty target set is intentionally library-wide only for cold-start work; a user command
     * without root ids is treated as having no scan target instead of silently falling back to a
     * global scan.
     */
    private fun ScanCommand.scopedRootUpdates(
        rootUpdates: List<LibraryRootAvailabilityUpdate>
    ): List<LibraryRootAvailabilityUpdate> {
        if (trigger == AudiobookSchema.ScanTrigger.COLD_START) return rootUpdates
        if (targetRootIds.isEmpty()) return emptyList()
        return rootUpdates.filter { update -> update.root.id in targetRootIds }
    }
}
