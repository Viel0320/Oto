package com.viel.aplayer.library

import com.viel.aplayer.data.BookFileEntity

// In-memory first-claim-wins ledger for one import run.
class RunClaimLedger {
    private val ownerByKey = mutableMapOf<String, ImportSourceRef>()

    fun reserve(
        source: ImportSourceRef,
        files: List<FileIdentity>,
        existingClaimIndex: ExistingClaimIndex
    ): ReservationResult {
        val existingHits = mutableListOf<BookFileEntity>()
        val runHits = mutableListOf<ImportSourceRef>()

        files.forEach { identity ->
            existingClaimIndex.find(identity)?.let { existingHits.add(it) }
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

        return ReservationResult(
            reserved = canReserve,
            existingConflicts = existingHits.distinctBy { it.id },
            runConflicts = runHits.distinct()
        )
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
