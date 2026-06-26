package com.viel.oto.library.availability

import com.viel.oto.data.db.AudiobookSchema

/**
 * Shared marker for source-provider exceptions that already carry canonical availability state.
 *
 * Playback error mapping depends on this VFS-level contract instead of protocol-specific ABS or
 * WebDAV exception classes, keeping media playback independent from individual remote adapters.
 */
interface RemoteAvailabilityException {
    val availabilityStatus: AudiobookSchema.AvailabilityStatus
}
