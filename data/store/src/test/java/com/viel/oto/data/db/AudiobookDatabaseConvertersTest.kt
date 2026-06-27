package com.viel.oto.data.db

import com.viel.oto.data.entity.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ensures Room serialization mapping stays robust.
 * Verifies enums translate correctly to strings, and that invalid strings fallback gracefully.
 */
class AudiobookDatabaseConvertersTest {

    private val converters = AudiobookDatabaseConverters()

    @Test
    fun testAbsPlaybackSessionStateConversion() {
        assertEquals("OPEN", converters.fromAbsPlaybackSessionState(AudiobookSchema.AbsPlaybackSessionState.OPEN))
        assertEquals("SYNCED", converters.fromAbsPlaybackSessionState(AudiobookSchema.AbsPlaybackSessionState.SYNCED))

        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState("OPEN"))
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.SYNCED, converters.toAbsPlaybackSessionState("SYNCED"))

        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState("INVALID"))
        assertEquals(AudiobookSchema.AbsPlaybackSessionState.OPEN, converters.toAbsPlaybackSessionState(""))
    }

    @Test
    fun testBookStatusConversion() {
        AudiobookSchema.BookStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromBookStatus(status))
            assertEquals(status, converters.toBookStatus(status.name))
        }
        assertEquals(AudiobookSchema.BookStatus.READY, converters.toBookStatus("INVALID"))
        assertEquals(AudiobookSchema.BookStatus.READY, converters.toBookStatus(""))
        assertEquals(AudiobookSchema.BookStatus.READY, converters.toBookStatus("ready"))
    }

    @Test
    fun testReadStatusConversion() {
        AudiobookSchema.ReadStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromReadStatus(status))
            assertEquals(status, converters.toReadStatus(status.name))
        }
        assertEquals(AudiobookSchema.ReadStatus.NOT_STARTED, converters.toReadStatus("INVALID"))
        assertEquals(AudiobookSchema.ReadStatus.NOT_STARTED, converters.toReadStatus(""))
    }

    @Test
    fun testAnchorStatusConversion() {
        AudiobookSchema.AnchorStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromAnchorStatus(status))
            assertEquals(status, converters.toAnchorStatus(status.name))
        }
        assertEquals(AudiobookSchema.AnchorStatus.OK, converters.toAnchorStatus("INVALID"))
        assertEquals(AudiobookSchema.AnchorStatus.OK, converters.toAnchorStatus(""))
    }

    @Test
    fun testSourceTypeConversion() {
        AudiobookSchema.SourceType.entries.forEach { type ->
            assertEquals(type.name, converters.fromSourceType(type))
            assertEquals(type, converters.toSourceType(type.name))
        }
        assertEquals(AudiobookSchema.SourceType.SINGLE_AUDIO, converters.toSourceType("INVALID"))
        assertEquals(AudiobookSchema.SourceType.SINGLE_AUDIO, converters.toSourceType(""))
    }

    @Test
    fun testFileRoleConversion() {
        AudiobookSchema.FileRole.entries.forEach { role ->
            assertEquals(role.name, converters.fromFileRole(role))
            assertEquals(role, converters.toFileRole(role.name))
        }
        assertEquals(AudiobookSchema.FileRole.AUDIO, converters.toFileRole("INVALID"))
        assertEquals(AudiobookSchema.FileRole.AUDIO, converters.toFileRole(""))
    }

    @Test
    fun testChapterSourceConversion() {
        AudiobookSchema.ChapterSource.entries.forEach { source ->
            assertEquals(source.name, converters.fromChapterSource(source))
            assertEquals(source, converters.toChapterSource(source.name))
        }
        assertEquals(AudiobookSchema.ChapterSource.EMBEDDED, converters.toChapterSource("INVALID"))
        assertEquals(AudiobookSchema.ChapterSource.EMBEDDED, converters.toChapterSource(""))
    }

    @Test
    fun testFileStatusConversion() {
        AudiobookSchema.FileStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromFileStatus(status))
            assertEquals(status, converters.toFileStatus(status.name))
        }
        assertEquals(AudiobookSchema.FileStatus.READY, converters.toFileStatus("INVALID"))
        assertEquals(AudiobookSchema.FileStatus.READY, converters.toFileStatus(""))
    }

    @Test
    fun testScanTriggerConversion() {
        AudiobookSchema.ScanTrigger.entries.forEach { trigger ->
            assertEquals(trigger.name, converters.fromScanTrigger(trigger))
            assertEquals(trigger, converters.toScanTrigger(trigger.name))
        }
        assertEquals(AudiobookSchema.ScanTrigger.USER, converters.toScanTrigger("INVALID"))
        assertEquals(AudiobookSchema.ScanTrigger.USER, converters.toScanTrigger(""))
    }

    @Test
    fun testScanStatusConversion() {
        AudiobookSchema.ScanStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromScanStatus(status))
            assertEquals(status, converters.toScanStatus(status.name))
        }
        assertEquals(AudiobookSchema.ScanStatus.COMPLETED, converters.toScanStatus("INVALID"))
        assertEquals(AudiobookSchema.ScanStatus.COMPLETED, converters.toScanStatus(""))
    }

    @Test
    fun testLibraryRootStatusConversion() {
        AudiobookSchema.LibraryRootStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromLibraryRootStatus(status))
            assertEquals(status, converters.toLibraryRootStatus(status.name))
        }
        assertEquals(AudiobookSchema.LibraryRootStatus.ACTIVE, converters.toLibraryRootStatus("INVALID"))
        assertEquals(AudiobookSchema.LibraryRootStatus.ACTIVE, converters.toLibraryRootStatus(""))
    }

    @Test
    fun testLibrarySourceTypeConversion() {
        AudiobookSchema.LibrarySourceType.entries.forEach { type ->
            assertEquals(type.name, converters.fromLibrarySourceType(type))
            assertEquals(type, converters.toLibrarySourceType(type.name))
        }
        assertEquals(AudiobookSchema.LibrarySourceType.SAF, converters.toLibrarySourceType("INVALID"))
        assertEquals(AudiobookSchema.LibrarySourceType.SAF, converters.toLibrarySourceType(""))
    }

    @Test
    fun testAbsMirrorStateConversion() {
        AudiobookSchema.AbsMirrorState.entries.forEach { state ->
            assertEquals(state.name, converters.fromAbsMirrorState(state))
            assertEquals(state, converters.toAbsMirrorState(state.name))
        }
        assertEquals(AudiobookSchema.AbsMirrorState.ACTIVE, converters.toAbsMirrorState("INVALID"))
        assertEquals(AudiobookSchema.AbsMirrorState.ACTIVE, converters.toAbsMirrorState(""))
    }

    @Test
    fun testAvailabilityStatusConversion() {
        AudiobookSchema.AvailabilityStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromAvailabilityStatus(status))
            assertEquals(status, converters.toAvailabilityStatus(status.name))
        }
        assertEquals(AudiobookSchema.AvailabilityStatus.UNKNOWN, converters.toAvailabilityStatus("INVALID"))
        assertEquals(AudiobookSchema.AvailabilityStatus.UNKNOWN, converters.toAvailabilityStatus(""))
    }

    @Test
    fun testDownloadStatusConversion() {
        DownloadStatus.entries.forEach { status ->
            assertEquals(status.name, converters.fromDownloadStatus(status))
            assertEquals(status, converters.toDownloadStatus(status.name))
        }
        assertEquals(DownloadStatus.FAILED, converters.toDownloadStatus("INVALID"))
        assertEquals(DownloadStatus.FAILED, converters.toDownloadStatus(""))
    }
}
