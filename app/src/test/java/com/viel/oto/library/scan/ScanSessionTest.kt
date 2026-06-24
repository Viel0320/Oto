package com.viel.oto.library.scan

import com.viel.oto.R
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.entity.ScanSessionEntity
import com.viel.oto.event.feedback.FeedbackMessage
import com.viel.oto.library.availability.AvailabilityResult
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
import com.viel.oto.library.orchestrator.RescanType
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
            importBlock = { _, _, allowedRootIds ->
                importCalls += allowedRootIds
                scanEntity()
            }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.USER,
                targetRootIds = setOf("webdav-1", "abs-1")
            )
        )

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
            importBlock = { _, _, allowedRootIds ->
                importCalls += allowedRootIds
                scanEntity()
            }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.USER,
                targetRootIds = setOf("saf-1", "webdav-1", "abs-1")
            )
        )

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
            importBlock = { _, _, allowedRootIds ->
                importCalls += allowedRootIds
                scanEntity()
            }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.USER,
                targetRootIds = setOf("abs-1")
            )
        )

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
            importBlock = { type, _, allowedRootIds ->
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
    fun `user command with target root should use root-scoped rescan type`() = runBlocking {
        var capturedType: RescanType? = null
        var capturedTargets: Set<String> = emptySet()
        var capturedAllowedRoots: Set<String> = emptySet()
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                ),
                rootUpdate(
                    id = "saf-2",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                ),
                rootUpdate(
                    id = "webdav-1",
                    sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT
                )
            ),
            importBlock = { type, targetRootIds, allowedRootIds ->
                capturedType = type
                capturedTargets = targetRootIds
                capturedAllowedRoots = allowedRootIds
                scanEntity(discovered = 1)
            }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.USER,
                targetRootIds = setOf("saf-2")
            )
        )

        assertEquals(RescanType.USER_ROOTS, capturedType)
        assertEquals(setOf("saf-2"), capturedTargets)
        assertEquals(setOf("saf-2"), capturedAllowedRoots)
        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
    }

    @Test
    fun `add library command with target root should use new-root rescan type`() = runBlocking {
        var capturedType: RescanType? = null
        var capturedTargets: Set<String> = emptySet()
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "new-root",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { type, targetRootIds, _ ->
                capturedType = type
                capturedTargets = targetRootIds
                scanEntity(discovered = 1)
            }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.ADD_LIBRARY_ROOT,
                targetRootIds = setOf("new-root")
            )
        )

        assertEquals(RescanType.NEW_LIBRARY_ROOT, capturedType)
        assertEquals(setOf("new-root"), capturedTargets)
        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
    }

    @Test
    fun `cold-start command with target root should scope import to that root only`() = runBlocking {
        var capturedType: RescanType? = null
        var capturedAllowedRoots: Set<String> = emptySet()
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                ),
                rootUpdate(
                    id = "saf-2",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { type, _, allowedRootIds ->
                capturedType = type
                capturedAllowedRoots = allowedRootIds
                scanEntity(discovered = 1)
            }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.COLD_START,
                targetRootIds = setOf("saf-1")
            )
        )

        assertEquals(RescanType.COLD_START_LIGHT, capturedType)
        assertEquals(setOf("saf-1"), capturedAllowedRoots)
        assertEquals(ScanOutcomeKind.SUCCESS, outcome.kind)
    }

    @Test
    fun `user command without target roots should not fall back to global import`() = runBlocking {
        val importCalls = mutableListOf<Set<String>>()
        val session = scanSession(
            roots = listOf(
                rootUpdate(
                    id = "saf-1",
                    sourceType = AudiobookSchema.LibrarySourceType.SAF,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
                )
            ),
            importBlock = { _, _, allowedRootIds ->
                importCalls += allowedRootIds
                scanEntity(discovered = 1)
            }
        )

        val outcome = session.execute(ScanCommand(trigger = AudiobookSchema.ScanTrigger.USER))

        assertEquals(ScanOutcomeKind.BLOCKED, outcome.kind)
        assertTrue(importCalls.isEmpty())
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
            importBlock = { _, _, _ -> scanEntity() },
            isLibraryEmpty = true
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.USER,
                targetRootIds = setOf("saf-1")
            )
        )

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
            importBlock = { _, _, _ -> abandonedSession },
            librarySnapshotBlock = {
                librarySnapshotCalls += 1
                false
            }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.USER,
                targetRootIds = setOf("saf-1")
            )
        )

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
            importBlock = { _, _, _ -> throw IOException("storage unavailable") }
        )

        val outcome = session.execute(
            ScanCommand(
                trigger = AudiobookSchema.ScanTrigger.USER,
                targetRootIds = setOf("saf-1")
            )
        )

        assertEquals(ScanOutcomeKind.RETRY, outcome.kind)
        assertTrue(outcome.cause is IOException)
        val message = outcome.feedback!!.message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_scan_retry_later, message.resId)
    }

    private fun scanSession(
        roots: List<LibraryRootAvailabilityUpdate>,
        importBlock: suspend (RescanType, Set<String>, Set<String>) -> ScanSessionEntity,
        isLibraryEmpty: Boolean = false,
        librarySnapshotBlock: suspend () -> Boolean = { isLibraryEmpty }
    ): ScanSession =
        ScanSession(
            rootStatusAdapter = ScanRootStatusAdapter { _ -> roots },
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
