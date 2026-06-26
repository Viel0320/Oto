package com.viel.oto.data.availability

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookFileEntity

/**
 * Data-owned reachability result for persisted audio files.
 *
 * Library adapters may derive the value from SAF, WebDAV, or ABS checks; data persistence code only needs the
 * normalized status language used by Room status fields.
 */
data class FileAvailabilityResult(
    val status: AudiobookSchema.AvailabilityStatus,
    val errorCode: String? = null,
    val message: String? = null
) {
    val isAvailable: Boolean
        get() = status == AudiobookSchema.AvailabilityStatus.AVAILABLE
}

/**
 * Narrow source reachability probe used by BookAvailabilityGatewayImpl.
 *
 * Keeps source access, VFS traversal, and remote protocol classification outside data while letting the gateway own
 * durable file and book status updates.
 */
interface FileAvailabilityProbe {
    /**
     * Checks one persisted audio file without writing Room status.
     */
    suspend fun checkBookFile(file: BookFileEntity): FileAvailabilityResult

    /**
     * Checks a batch of persisted audio files and returns results keyed by file ID.
     */
    suspend fun checkBookFiles(files: List<BookFileEntity>): Map<String, FileAvailabilityResult>
}
