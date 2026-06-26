package com.viel.oto.library.availability

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceKind

/**
 * Carries persisted root state after a sync preflight check.
 * Stores both the updated LibraryRootEntity and the low-level AvailabilityResult so callers can decide whether a sync may start and explain blocked roots to the user.
 */
data class LibraryRootAvailabilityUpdate(
    val root: LibraryRootEntity,
    val availability: AvailabilityResult
)

/**
 * Converts refreshed reachability state into a start-or-block decision.
 * Requires both the public root status and the protocol-specific availability probe to be healthy before any scan or ABS catalog sync proceeds.
 */
val LibraryRootAvailabilityUpdate.isSyncAvailable: Boolean
    get() = root.status == AudiobookSchema.LibraryRootStatus.ACTIVE && availability.isAvailable

/**
 * Limits file-tree scans to providers that expose enumerable directories.
 * Excludes ABS because ABS catalog mirroring is handled through its REST synchronization path rather than SourceInventoryScanner directory traversal.
 */
fun LibraryRootEntity.isDirectorySyncRoot(): Boolean =
    when (LibrarySourceKind.from(sourceType)) {
        LibrarySourceKind.SAF,
        LibrarySourceKind.WEBDAV -> true
        LibrarySourceKind.ABS,
        null -> false
    }
