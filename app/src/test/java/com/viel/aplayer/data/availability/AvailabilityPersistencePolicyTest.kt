package com.viel.aplayer.data.availability

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.availability.AvailabilityResult
import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilityPersistencePolicyTest {
    @Test
    fun `transient remote failures should preserve previous file status`() {
        listOf(
            AudiobookSchema.AvailabilityStatus.TIMEOUT,
            AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE,
            AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
            AudiobookSchema.AvailabilityStatus.AUTH_FAILED,
            AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED,
            AudiobookSchema.AvailabilityStatus.UNKNOWN
        ).forEach { status ->
            assertEquals(
                AudiobookSchema.FileStatus.READY,
                AvailabilityPersistencePolicy.nextFileStatus(
                    previousStatus = AudiobookSchema.FileStatus.READY,
                    result = AvailabilityResult(status = status)
                )
            )
        }
    }

    @Test
    fun `available and not found probes should update durable file status`() {
        assertEquals(
            AudiobookSchema.FileStatus.READY,
            AvailabilityPersistencePolicy.nextFileStatus(
                previousStatus = AudiobookSchema.FileStatus.MISSING,
                result = AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            )
        )
        assertEquals(
            AudiobookSchema.FileStatus.MISSING,
            AvailabilityPersistencePolicy.nextFileStatus(
                previousStatus = AudiobookSchema.FileStatus.READY,
                result = AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.NOT_FOUND)
            )
        )
    }
}
