package com.viel.aplayer.library.availability

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind

/**
 * Refreshed Root Availability Snapshot (Carries persisted root state after a sync preflight check)
 * Stores both the updated LibraryRootEntity and the low-level AvailabilityResult so callers can decide whether a sync may start and explain blocked roots to the user.
 */
data class LibraryRootAvailabilityUpdate(
    val root: LibraryRootEntity,
    val availability: AvailabilityResult
)

/**
 * Sync Eligibility Decision (Converts refreshed reachability state into a start-or-block decision)
 * Requires both the public root status and the protocol-specific availability probe to be healthy before any scan or ABS catalog sync proceeds.
 */
internal val LibraryRootAvailabilityUpdate.isSyncAvailable: Boolean
    get() = root.status == AudiobookSchema.LibraryRootStatus.ACTIVE && availability.isAvailable

/**
 * Directory Sync Root Filter (Limits file-tree scans to providers that expose enumerable directories)
 * Excludes ABS because ABS catalog mirroring is handled through its REST synchronization path rather than SourceInventoryScanner directory traversal.
 */
internal fun LibraryRootEntity.isDirectorySyncRoot(): Boolean =
    when (LibrarySourceKind.from(sourceType)) {
        LibrarySourceKind.SAF,
        LibrarySourceKind.WEBDAV -> true
        LibrarySourceKind.ABS,
        null -> false
    }

/**
 * Unavailable Root Feedback Builder (Creates a resource-backed skip message for one blocked root)
 * Keeps availability status as stable codes here while FeedbackMessages owns the localized wording and formatting.
 */
internal fun buildRootUnavailableSyncMessage(update: LibraryRootAvailabilityUpdate): FeedbackMessage {
    val rootName = update.root.displayName.ifBlank { update.root.sourceUri }
    // Sync Preflight Feedback: Use availability status enum name when errorCode is missing.
    return FeedbackMessages.libraryRootUnavailableSync(
        rootName = rootName,
        availabilityStatus = update.availability.status,
        fallbackCode = update.availability.errorCode ?: update.availability.status.name
    )
}

/**
 * Unavailable Roots Feedback Builder (Creates a compact resource-backed skip message for global scans)
 * Multiple-root feedback reports a count instead of joining localized names with hard-coded punctuation.
 */
internal fun buildUnavailableRootsSyncMessage(updates: List<LibraryRootAvailabilityUpdate>): FeedbackMessage {
    if (updates.isEmpty()) return FeedbackMessages.libraryRootsUnavailableNone()
    if (updates.size == 1) return buildRootUnavailableSyncMessage(updates.first())
    return FeedbackMessages.libraryRootsUnavailableSync(updates.size)
}
