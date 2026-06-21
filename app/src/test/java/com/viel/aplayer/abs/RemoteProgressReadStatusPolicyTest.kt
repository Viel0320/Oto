package com.viel.aplayer.abs

import com.viel.aplayer.abs.mapping.RemoteProgressReadStatusPolicy
import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteProgressReadStatusPolicyTest {
    @Test
    fun `finished remote progress wins over position`() {
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
