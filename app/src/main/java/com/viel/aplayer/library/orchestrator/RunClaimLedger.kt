package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

// In-memory first-claim-wins ledger for one import run.
class RunClaimLedger(
    // Share Scan Session State (Ownership Integrity)
    // Allows ScopeOrchestrator to pass a shared owner map to preserve claim state across scope limits.
    private val ownerByKey: MutableMap<String, ImportSourceRef> = mutableMapOf()
) {

    /**
     * Reserve Physical Tracks (Claim Allocation)
     *
     * Allocates and reserves files for an import source.
     * Incorporates currentParentSourcePath to localize checks within the same VFS directory,
     * ensuring existing claimed files are verified correctly.
     *
     * @param source Source of the current claim
     * @param files Identifiers of the files to reserve
     * @param existingClaimIndex Index containing all database claims
     * @param currentParentSourcePath VFS parent directory path of the tracks
     * @return Result of the reservation attempt
     */
    fun reserve(
        source: ImportSourceRef,
        files: List<FileIdentity>,
        existingClaimIndex: ExistingClaimIndex,
        currentParentSourcePath: String? = null
    ): ReservationResult {
        // Synchronized Map Access (Thread Safety Protection)
        // Locks the shared ownerByKey map to prevent concurrent threads from creating race conditions or inconsistent states during checks.
        return synchronized(ownerByKey) {
            val existingHits = mutableListOf<BookFileEntity>()
            val runHits = mutableListOf<ImportSourceRef>()

            files.forEach { identity ->
                // Localized Folder Queries (Storage Decoupling)
                // Forwards parent path to localized index checks, removing obsolete parent URI compatibility pathways.
                existingClaimIndex.find(identity, currentParentSourcePath)?.let { existingHits.add(it) }
                identity.keys().forEach { key ->
                    ownerByKey[key]?.let { runHits.add(it) }
                }
            }

            val canReserve = existingHits.isEmpty() && runHits.isEmpty()
            if (canReserve) {
                files.forEach { identity ->
                    identity.keys().forEach { key -> ownerByKey.putIfAbsent(key, source) }
                }
            }

            ReservationResult(
                reserved = canReserve,
                existingConflicts = existingHits.distinctBy { it.id },
                runConflicts = runHits.distinct()
            )
        }
    }

    // Fork Ledger for Scope Execution (State Protection)
    // Synchronizes copy operations to isolate scoped claims, merging them only upon successful writes to prevent ConcurrentModificationException.
    fun fork(): RunClaimLedger {
        return synchronized(ownerByKey) {
            RunClaimLedger(ownerByKey.toMutableMap())
        }
    }

    // Commit Isolated Scope Claims (State Synchronization)
    // Synchronizes the merge operation to atomically consolidate scoped claims into the global scan session registry.
    fun commitFrom(scopeLedger: RunClaimLedger) {
        synchronized(ownerByKey) {
            scopeLedger.ownerByKey.forEach { (key, owner) ->
                ownerByKey.putIfAbsent(key, owner)
            }
        }
    }

    // Promote Resolved Replacement Claim (Conflict resolver handoff)
    // Marks a higher-priority replacement as the in-flight owner after policy resolution so later sources in the same scan cannot duplicate the same reassignment.
    fun promoteResolvedClaim(source: ImportSourceRef, files: List<FileIdentity>) {
        synchronized(ownerByKey) {
            files.forEach { identity ->
                identity.keys().forEach { key -> ownerByKey.putIfAbsent(key, source) }
            }
        }
    }
}

data class ImportSourceRef(
    val sourceType: String,
    val sourceUri: String,
    val displayName: String
)

data class ReservationResult(
    val reserved: Boolean,
    val existingConflicts: List<BookFileEntity>,
    val runConflicts: List<ImportSourceRef>
)
