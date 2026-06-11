package com.viel.aplayer.abs.mapping

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Remote Progress Read Status Policy (Translates authoritative ABS progress into local read-state language)
 * Keeps catalog sync, authorized progress sync, and playback conflict acceptance aligned when server progress becomes the selected source of truth.
 */
object RemoteProgressReadStatusPolicy {
    /**
     * Remote Progress Status Resolution (Applies completion before position and preserves an optional existing fallback)
     * Finished server checkpoints always become FINISHED, positive positions become IN_PROGRESS, and empty checkpoints keep the caller-provided status when available.
     */
    // Update signature and return type to ReadStatus enum for type safety.
    fun fromRemoteProgress(
        isFinished: Boolean?,
        hasPositivePosition: Boolean,
        existingReadStatus: AudiobookSchema.ReadStatus? = null
    ): AudiobookSchema.ReadStatus =
        when {
            isFinished == true -> AudiobookSchema.ReadStatus.FINISHED
            hasPositivePosition -> AudiobookSchema.ReadStatus.IN_PROGRESS
            existingReadStatus != null -> existingReadStatus
            else -> AudiobookSchema.ReadStatus.NOT_STARTED
        }
}
