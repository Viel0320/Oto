package com.viel.oto.library.availability

import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity

/**
 * ABS availability operations required by generic library availability checks.
 *
 * The library domain owns how availability snapshots are stored and consumed, while the ABS domain
 * owns the protocol calls, credentials, token refresh behavior, and source-specific error mapping.
 */
interface AbsAvailabilityGateway {
    suspend fun checkRoot(root: LibraryRootEntity): AvailabilityResult
    suspend fun checkBookFile(root: LibraryRootEntity, file: BookFileEntity): AvailabilityResult
}
