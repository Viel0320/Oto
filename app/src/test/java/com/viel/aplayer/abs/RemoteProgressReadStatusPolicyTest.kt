package com.viel.aplayer.abs

import com.viel.aplayer.abs.mapping.RemoteProgressReadStatusPolicy
import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteProgressReadStatusPolicyTest {
    @Test
    fun `finished remote progress wins over position`() {
        // Finished Remote Authority (Documents that ABS completion is a semantic read-state signal)
        // A server-finished checkpoint should become FINISHED even if the mapped position is zero or missing.
        assertEquals(
            AudiobookSchema.ReadStatus.FINISHED,
            RemoteProgressReadStatusPolicy.fromRemoteProgress(
                isFinished = true,
                hasPositivePosition = false
            )
        )
    }

    @Test
    fun `positive remote position becomes in progress`() {
        // Positive Position Status Mapping (Converts playable server checkpoints into local in-progress state)
        // This keeps catalog sync, authorized progress sync, and playback conflict acceptance consistent for non-finished checkpoints.
        assertEquals(
            AudiobookSchema.ReadStatus.IN_PROGRESS,
            RemoteProgressReadStatusPolicy.fromRemoteProgress(
                isFinished = false,
                hasPositivePosition = true
            )
        )
    }

    @Test
    fun `empty remote progress preserves explicit fallback or starts fresh`() {
        // Empty Progress Fallback (Preserves existing catalog read state when the server has no usable checkpoint)
        // First-time materialization still defaults to NOT_STARTED when no existing state is supplied.
        assertEquals(
            AudiobookSchema.ReadStatus.FINISHED,
            RemoteProgressReadStatusPolicy.fromRemoteProgress(
                isFinished = false,
                hasPositivePosition = false,
                existingReadStatus = AudiobookSchema.ReadStatus.FINISHED
            )
        )
        assertEquals(
            AudiobookSchema.ReadStatus.NOT_STARTED,
            RemoteProgressReadStatusPolicy.fromRemoteProgress(
                isFinished = null,
                hasPositivePosition = false
            )
        )
    }
}
