package com.viel.aplayer.library

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.AvailabilityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryRootLifecyclePolicyTest {
    @Test
    fun `binding refresh makes root active and clears stale availability diagnostics`() {
        val staleRoot = sampleRoot(
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            status = AudiobookSchema.LibraryRootStatus.ERROR,
            availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
            checkedAt = 999L,
            errorCode = "TIMEOUT"
        )

        val refreshed = LibraryRootLifecyclePolicy.markBindingRefreshed(staleRoot)

        // Binding Refresh Contract (Pins the exact lifecycle fields reset after root edits or credential replacement)
        // Identity, source address, credentials, and labels remain caller-owned, while stale availability details are discarded before the next probe.
        assertEquals(staleRoot.id, refreshed.id)
        assertEquals(staleRoot.sourceType, refreshed.sourceType)
        assertEquals(staleRoot.sourceUri, refreshed.sourceUri)
        assertEquals(staleRoot.basePath, refreshed.basePath)
        assertEquals(staleRoot.credentialId, refreshed.credentialId)
        assertEquals(staleRoot.displayName, refreshed.displayName)
        assertEquals(AudiobookSchema.LibraryRootStatus.ACTIVE, refreshed.status)
        assertEquals(AudiobookSchema.AvailabilityStatus.UNKNOWN, refreshed.availabilityStatus)
        assertEquals(0L, refreshed.lastAvailabilityCheckedAt)
        assertNull(refreshed.lastAvailabilityErrorCode)
    }

    @Test
    fun `available probe makes every source active`() {
        listOf(
            AudiobookSchema.LibrarySourceType.SAF,
            AudiobookSchema.LibrarySourceType.WEBDAV,
            AudiobookSchema.LibrarySourceType.ABS
        ).forEach { sourceType ->
            val updated = LibraryRootLifecyclePolicy.applyAvailabilitySnapshot(
                root = sampleRoot(sourceType = sourceType, status = AudiobookSchema.LibraryRootStatus.ERROR),
                availability = AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.AVAILABLE,
                    checkedAt = 123L
                )
            )

            // Available Probe Contract (Documents that successful reachability always restores the root lifecycle)
            // SAF grants, WebDAV endpoints, and ABS catalog roots all become ACTIVE when the checker reports AVAILABLE.
            assertEquals(AudiobookSchema.LibraryRootStatus.ACTIVE, updated.status)
            assertEquals(AudiobookSchema.AvailabilityStatus.AVAILABLE, updated.availabilityStatus)
            assertEquals(123L, updated.lastAvailabilityCheckedAt)
        }
    }

    @Test
    fun `unavailable saf probe becomes revoked while remote probes become error`() {
        val saf = LibraryRootLifecyclePolicy.applyAvailabilitySnapshot(
            root = sampleRoot(sourceType = AudiobookSchema.LibrarySourceType.SAF),
            availability = AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                checkedAt = 200L,
                // Update LibraryRootLifecyclePolicyTest: Use .name for AvailabilityStatus enum in AvailabilityResult errorCode.
                errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name
            )
        )
        val webDav = LibraryRootLifecyclePolicy.applyAvailabilitySnapshot(
            root = sampleRoot(sourceType = AudiobookSchema.LibrarySourceType.WEBDAV),
            availability = AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                checkedAt = 201L,
                errorCode = "TIMEOUT"
            )
        )
        val abs = LibraryRootLifecyclePolicy.applyAvailabilitySnapshot(
            root = sampleRoot(sourceType = AudiobookSchema.LibrarySourceType.ABS),
            availability = AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.AUTH_FAILED,
                checkedAt = 202L,
                errorCode = "AUTH_FAILED"
            )
        )

        // Unavailable Source Classification (Separates local permission repair from remote operational failures)
        // SAF retains the revoked-root behavior, while WebDAV and ABS keep remote failures in ERROR for diagnostics and retry handling.
        assertEquals(AudiobookSchema.LibraryRootStatus.REVOKED, saf.status)
        assertEquals(AudiobookSchema.LibraryRootStatus.ERROR, webDav.status)
        assertEquals(AudiobookSchema.LibraryRootStatus.ERROR, abs.status)
        assertEquals(AudiobookSchema.AvailabilityStatus.NOT_FOUND, saf.availabilityStatus)
        assertEquals(AudiobookSchema.AvailabilityStatus.TIMEOUT, webDav.availabilityStatus)
        assertEquals(AudiobookSchema.AvailabilityStatus.AUTH_FAILED, abs.availabilityStatus)
        assertEquals(200L, saf.lastAvailabilityCheckedAt)
        assertEquals(201L, webDav.lastAvailabilityCheckedAt)
        assertEquals(202L, abs.lastAvailabilityCheckedAt)
    }

    // Update LibraryRootLifecyclePolicyTest: Change sampleRoot helper signature to use type-safe enums.
    private fun sampleRoot(
        sourceType: AudiobookSchema.LibrarySourceType,
        status: AudiobookSchema.LibraryRootStatus = AudiobookSchema.LibraryRootStatus.ACTIVE,
        availabilityStatus: AudiobookSchema.AvailabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
        checkedAt: Long = 0L,
        errorCode: String? = null
    ): LibraryRootEntity =
        LibraryRootEntity(
            id = "root-$sourceType",
            sourceType = sourceType,
            sourceUri = "https://example.com/$sourceType",
            basePath = "library-1",
            credentialId = "credential-1",
            availabilityStatus = availabilityStatus,
            lastAvailabilityCheckedAt = checkedAt,
            lastAvailabilityErrorCode = errorCode,
            displayName = "$sourceType Library",
            status = status
        )
}
