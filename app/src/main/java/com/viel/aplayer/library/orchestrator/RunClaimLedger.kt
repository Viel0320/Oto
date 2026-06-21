package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

class RunClaimLedger(
    private val ownerByKey: MutableMap<String, ImportSourceRef> = mutableMapOf()
) {

    /**
     * Claim Allocation.
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
        return synchronized(ownerByKey) {
            val existingHits = mutableListOf<BookFileEntity>()
            val runHits = mutableListOf<ImportSourceRef>()

            files.forEach { identity ->
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

    fun fork(): RunClaimLedger {
        return synchronized(ownerByKey) {
            RunClaimLedger(ownerByKey.toMutableMap())
        }
    }

    fun commitFrom(scopeLedger: RunClaimLedger) {
        synchronized(ownerByKey) {
            scopeLedger.ownerByKey.forEach { (key, owner) ->
                ownerByKey.putIfAbsent(key, owner)
            }
        }
    }

    fun promoteResolvedClaim(source: ImportSourceRef, files: List<FileIdentity>) {
        synchronized(ownerByKey) {
            files.forEach { identity ->
                identity.keys().forEach { key -> ownerByKey.putIfAbsent(key, source) }
            }
        }
    }
}

data class ImportSourceRef(
    val sourceType: AudiobookSchema.SourceType,
    val sourceUri: String,
    val displayName: String
)

data class ReservationResult(
    val reserved: Boolean,
    val existingConflicts: List<BookFileEntity>,
    val runConflicts: List<ImportSourceRef>
)
