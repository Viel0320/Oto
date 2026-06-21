package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

/**
 * Import domain policy.
 *
 * Owns the deterministic source-priority rule for file ownership conflicts.
 * The pipeline asks this resolver what to do; database mutation and draft construction stay outside this class.
 */
internal class ConflictResolver {

    /**
     * CUE/M3U8 conflict policy.
     *
     * Applies cue > m3u8 > other for persisted owners, turns missing manifest references into PARTIAL imports,
     * and returns refresh/replace/skip commands without touching the database.
     */
    fun resolveManifestOwnership(
        source: ImportSourceRef,
        claimedIdentities: List<FileIdentity>,
        reservation: ReservationResult,
        existingClaimIndex: ExistingClaimIndex,
        missingCount: Int
    ): ConflictResolution {
        val bookStatus = if (missingCount > 0) {
            AudiobookSchema.BookStatus.PARTIAL
        } else {
            AudiobookSchema.BookStatus.READY
        }

        if (reservation.reserved) {
            return ConflictResolution.CreateBook(bookStatus)
        }

        if (reservation.runConflicts.isNotEmpty()) {
            return ConflictResolution.Skip
        }

        val completeClaim = existingClaimIndex.completeExistingClaim(claimedIdentities)
        if (completeClaim != null) {
            return if (missingCount == 0) {
                ConflictResolution.RefreshBook(
                    bookId = completeClaim.bookId,
                    files = completeClaim.files
                )
            } else {
                ConflictResolution.ReplaceBooks(
                    bookIds = listOf(completeClaim.bookId),
                    bookStatus = bookStatus
                )
            }
        }

        val existingBookIds = reservation.existingConflicts.map { it.bookId }.distinct()
        if (existingBookIds.isEmpty()) {
            return ConflictResolution.Skip
        }

        val incomingPriority = sourcePriority(source.sourceType)
        val canReplacePersistedOwners = existingBookIds.all { bookId ->
            incomingPriority > sourcePriority(existingClaimIndex.sourceTypeForBook(bookId))
        }
        return if (canReplacePersistedOwners) {
            ConflictResolution.ReplaceBooks(
                bookIds = existingBookIds,
                bookStatus = bookStatus
            )
        } else {
            ConflictResolution.Skip
        }
    }

    private fun sourcePriority(sourceType: AudiobookSchema.SourceType?): Int =
        when (sourceType) {
            AudiobookSchema.SourceType.CUE -> 3
            AudiobookSchema.SourceType.M3U8 -> 2
            else -> 1
        }
}

/**
 * Import decision contract.
 *
 * Represents what the scan pipeline should emit after comparing an incoming owner with existing reservations.
 */
internal sealed interface ConflictResolution {
    /**
     * Uncontested ownership.
     *
     * Indicates that the incoming source owns its files and should be inserted as READY or PARTIAL.
     */
    data class CreateBook(val bookStatus: AudiobookSchema.BookStatus) : ConflictResolution

    /**
     * Idempotent rescan.
     *
     * Indicates that all claimed files already belong to the same book and only visibility markers need refreshing.
     */
    data class RefreshBook(
        val bookId: String,
        val files: List<BookFileEntity>
    ) : ConflictResolution

    /**
     * Priority reassignment.
     *
     * Indicates that lower-priority or stale same-owner rows should be removed after state migration.
     */
    data class ReplaceBooks(
        val bookIds: List<String>,
        val bookStatus: AudiobookSchema.BookStatus
    ) : ConflictResolution

    /**
     * Winner already exists.
     *
     * Indicates that an equal/higher-priority owner already holds the files, so no command should be emitted.
     */
    data object Skip : ConflictResolution
}
