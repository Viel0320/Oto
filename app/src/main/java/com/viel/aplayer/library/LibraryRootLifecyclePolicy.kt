package com.viel.aplayer.library

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.AvailabilityResult

/**
 * Library Root Lifecycle Policy (Centralizes root authorization and availability state transitions)
 * Keeps pure LibraryRootEntity state rules away from credential persistence, DAO ordering, and source-provider probes.
 */
object LibraryRootLifecyclePolicy {
    /**
     * Binding Refresh Snapshot (Resets stale availability after a root is re-authorized or rebound)
     * Root edits and credential replacements should make the source scan-eligible again while forcing the next availability probe to produce fresh diagnostics.
     */
    fun markBindingRefreshed(root: LibraryRootEntity): LibraryRootEntity =
        root.copy(
            status = AudiobookSchema.LibraryRootStatus.ACTIVE,
            availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
            lastAvailabilityCheckedAt = 0L,
            lastAvailabilityErrorCode = null
        )

    /**
     * Availability Snapshot Application (Projects a checker result onto the persisted root lifecycle fields)
     * This keeps the high-level LibraryRootStatus and detailed AvailabilityStatus aligned for settings rows, scan preflights, and sync guards.
     */
    fun applyAvailabilitySnapshot(
        root: LibraryRootEntity,
        availability: AvailabilityResult
    ): LibraryRootEntity =
        root.copy(
            status = rootStatusFor(root.sourceType, availability),
            availabilityStatus = availability.status,
            lastAvailabilityCheckedAt = availability.checkedAt,
            lastAvailabilityErrorCode = availability.errorCode
        )

    /**
     * Root Status Resolution (Separates local grant loss from remote infrastructure failure)
     * Available roots return to ACTIVE, SAF failures become REVOKED, and remote-source failures become ERROR so callers can distinguish permission repair from network/server repair.
     */
    // Root Status Resolver: Resolve the correct LibraryRootStatus enum based on sourceType and check result.
    fun rootStatusFor(
        sourceType: AudiobookSchema.LibrarySourceType,
        availability: AvailabilityResult
    ): AudiobookSchema.LibraryRootStatus =
        when {
            availability.isAvailable -> AudiobookSchema.LibraryRootStatus.ACTIVE
            sourceType == AudiobookSchema.LibrarySourceType.SAF -> AudiobookSchema.LibraryRootStatus.REVOKED
            else -> AudiobookSchema.LibraryRootStatus.ERROR
        }
}
