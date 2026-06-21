package com.viel.aplayer.library.scan

import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.library.availability.AvailabilityResult
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import com.viel.aplayer.library.orchestrator.RescanType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScanSessionTest {

    @Test
    fun `blocked command should not start import when no scan-capable library is available`() = runBlocking {
        val importCalls = mutableListOf<Set<String>>()
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "webdav-1",
                    sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT
                ),
                rootUpdate(
                    id = "abs-1",
                    sourceType = AudiobookSchema.LibrarySourceType.ABS,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { _, allowedRootIds ->
                importCalls += allowedRootIds
                scanEntity()
            }
        )

        val outcome = session.execute(ScanCommand(AudiobookSchema.ScanTrigger.USER))

        assertEquals(ScanOutcomeKind.BLOCKED, outcome.kind)
        assertNull(outcome.session)
        assertTrue(importCalls.isEmpty())
        val message = outcome.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_sync_root_unavailable_timeout, message.resId)
    }

    @Test
    fun `blocked command reports no available library only after all access forms fail`() = runBlocking {
        val importCalls = mutableListOf<Set<String>>()
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.REVOKED
                ),
                rootUpdate(
                    id = "webdav-1",
                    sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT
                ),
                rootUpdate(
                    id = "abs-1",
                    sourceType = AudiobookSchema.LibrarySourceType.ABS,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AUTH_FAILED
                )
            ),
            importBlock = { _, allowedRootIds ->
                importCalls += allowedRootIds
                scanEntity()
            }
        )

        val outcome = session.execute(ScanCommand(AudiobookSchema.ScanTrigger.USER))

        assertEquals(ScanOutcomeKind.BLOCKED, outcome.kind)
        assertTrue(importCalls.isEmpty())
        val message = outcome.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_blocked_no_available_libraries, message.resId)
    }

    @Test
    fun `available abs root prevents no-library feedback when directory importer has no work`() = runBlocking {
        val importCalls = mutableListOf<Set<String>>()
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "abs-1",
                    sourceType = AudiobookSchema.LibrarySourceType.ABS,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { _, allowedRootIds ->
                importCalls += allowedRootIds
                scanEntity()
            }
        )

        val outcome = session.execute(ScanCommand(AudiobookSchema.ScanTrigger.USER))

        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
        assertTrue(importCalls.isEmpty())
        val message = outcome.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_already_up_to_date, message.resId)
    }

    @Test
    fun `completed command should pass only available directory roots and report skipped roots as partial`() = runBlocking {
        var capturedType: RescanType? = null
        var capturedRoots: Set<String> = emptySet()
        val completedSession = scanEntity(discovered = 1)
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                ),
                rootUpdate(
                    id = "webdav-1",
                    sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT
                ),
                rootUpdate(
                    id = "abs-1",
                    sourceType = AudiobookSchema.LibrarySourceType.ABS,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { type, allowedRootIds ->
                capturedType = type
                capturedRoots = allowedRootIds
                completedSession
            },
            isLibraryEmpty = false
        )

        val outcome = session.execute(ScanCommand(AudiobookSchema.ScanTrigger.COLD_START))

        assertEquals(RescanType.COLD_START_LIGHT, capturedType)
        assertEquals(setOf("saf-1"), capturedRoots)
        assertEquals(ScanOutcomeKind.PARTIAL, outcome.kind)
        assertEquals(completedSession, outcome.session)
    }

    @Test
    fun `completed empty library should use library snapshot adapter for outcome message`() = runBlocking {
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { _, _ -> scanEntity() },
            isLibraryEmpty = true
        )

        val outcome = session.execute(ScanCommand(AudiobookSchema.ScanTrigger.USER))

        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
        val message = outcome.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_library_empty, message.resId)
    }

    @Test
    fun `abandoned import session should fail instead of reporting successful scan`() = runBlocking {
        var librarySnapshotCalls = 0
        val abandonedSession = scanEntity(status = AudiobookSchema.ScanStatus.ABANDONED)
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { _, _ -> abandonedSession },
            librarySnapshotBlock = {
                librarySnapshotCalls += 1
                false
            }
        )

        val outcome = session.execute(ScanCommand(AudiobookSchema.ScanTrigger.USER))

        assertEquals(ScanOutcomeKind.FAILED, outcome.kind)
        assertNull(outcome.session)
        assertTrue(outcome.cause is IllegalStateException)
        assertEquals(0, librarySnapshotCalls)
    }

    @Test
    fun `io failure from adapters should map to retry without swallowing cancellation policy`() = runBlocking {
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { _, _ -> throw IOException("storage unavailable") }
        )

        val outcome = session.execute(ScanCommand(AudiobookSchema.ScanTrigger.USER))

        assertEquals(ScanOutcomeKind.RETRY, outcome.kind)
        assertTrue(outcome.cause is IOException)
        val message = outcome.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_retry_later, message.resId)
    }

    private fun scanSession(
        roots: List<LibraryRootAvailabilityUpdate>,
        importBlock: suspend (RescanType, Set<String>) -> ScanSessionEntity,
        isLibraryEmpty: Boolean = false,
        librarySnapshotBlock: suspend () -> Boolean = { isLibraryEmpty }
    ): ScanSession =
        ScanSession(
            rootStatusAdapter = ScanRootStatusAdapter { roots },
            importAdapter = ScanImportAdapter(importBlock),
            librarySnapshotAdapter = ScanLibrarySnapshotAdapter(librarySnapshotBlock)
        )

    private fun rootUpdate(
        id: String,
        sourceType: AudiobookSchema.LibrarySourceType,
        availabilityStatus: AudiobookSchema.AvailabilityStatus
    ): LibraryRootAvailabilityUpdate =
        LibraryRootAvailabilityUpdate(
            root = LibraryRootEntity(
                id = id,
                sourceType = sourceType,
                sourceUri = "uri:$id",
                displayName = id,
                status = if (availabilityStatus == AudiobookSchema.AvailabilityStatus.AVAILABLE) {
                    AudiobookSchema.LibraryRootStatus.ACTIVE
                } else {
                    AudiobookSchema.LibraryRootStatus.ERROR
                }
            ),
            availability = AvailabilityResult(status = availabilityStatus)
        )

    private fun scanEntity(
        discovered: Int = 0,
        status: AudiobookSchema.ScanStatus = AudiobookSchema.ScanStatus.COMPLETED
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = "scan-1",
            trigger = AudiobookSchema.ScanTrigger.USER,
            status = status,
            discoveredBookCount = discovered
        )
}
