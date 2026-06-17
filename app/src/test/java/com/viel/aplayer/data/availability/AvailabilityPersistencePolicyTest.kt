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
            // Transient Failure Persistence Guard (Prevents temporary remote faults from becoming durable missing files)
            // The availability refresh layer must keep the existing Room file status unless the probe proves that the file is actually gone.
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
        // Durable Probe Mapping (Documents the only two probe outcomes that rewrite file availability)
        // AVAILABLE restores playback eligibility, while NOT_FOUND is the confirmed absence state that may mark a file missing.
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
