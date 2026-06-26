package com.viel.oto.media.service

import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.media.BookPlaybackPlan
import com.viel.oto.media.PlaybackDomainEvent
import com.viel.oto.media.PlaybackDomainEventDeliveryResult
import com.viel.oto.media.PlaybackDomainEventSink
import com.viel.oto.media.PlaybackSourcePreflight
import com.viel.oto.shared.model.PlaybackSourcePreflightBlockReason
import com.viel.oto.shared.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumptionPreflightTest {
    @Test
    fun `missing root blocks resumption and emits source preflight event`() = runBlocking {
        val eventSink = RecordingPlaybackDomainEventSink()
        val preflight = playbackResumptionPreflight(root = null, eventSink = eventSink)

        val error = runCatching { preflight.requireAvailable(playbackPlan(rootId = ROOT_ID)) }.exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertEquals(
            listOf(
                PlaybackDomainEvent.SourcePreflightBlocked(
                    reason = PlaybackSourcePreflightBlockReason.MissingRoot,
                    rootName = null,
                    bookTitle = "Resume Fixture"
                )
            ),
            eventSink.emittedEvents
        )
    }

    @Test
    fun `inactive root blocks resumption and includes root name in event`() = runBlocking {
        val eventSink = RecordingPlaybackDomainEventSink()
        val preflight = playbackResumptionPreflight(
            root = libraryRoot(status = AudiobookSchema.LibraryRootStatus.REVOKED),
            eventSink = eventSink
        )

        val error = runCatching { preflight.requireAvailable(playbackPlan(rootId = ROOT_ID)) }.exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertEquals(
            listOf(
                PlaybackDomainEvent.SourcePreflightBlocked(
                    reason = PlaybackSourcePreflightBlockReason.UnavailableRoot,
                    rootName = ROOT_NAME,
                    bookTitle = "Resume Fixture"
                )
            ),
            eventSink.emittedEvents
        )
    }

    @Test
    fun `cleartext http root blocks resumption and emits cleartext event`() = runBlocking {
        val eventSink = RecordingPlaybackDomainEventSink()
        val preflight = playbackResumptionPreflight(
            root = libraryRoot(
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                sourceUri = "http://example.com/audiobooks"
            ),
            settings = AppSettings(isCleartextTrafficAllowed = false),
            eventSink = eventSink
        )

        val error = runCatching { preflight.requireAvailable(playbackPlan(rootId = ROOT_ID)) }.exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertEquals(listOf(PlaybackDomainEvent.CleartextPlaybackBlocked("Resume Fixture")), eventSink.emittedEvents)
    }

    private fun playbackResumptionPreflight(
        root: LibraryRootEntity?,
        settings: AppSettings = AppSettings(),
        eventSink: RecordingPlaybackDomainEventSink
    ): PlaybackResumptionPreflight =
        PlaybackResumptionPreflight(
            playbackSourcePreflight = PlaybackSourcePreflight(FakeLibraryRootDao(root)),
            settingsProvider = { settings },
            playbackEventSink = eventSink
        )

    private class RecordingPlaybackDomainEventSink : PlaybackDomainEventSink {
        private val mutableEvents = MutableSharedFlow<PlaybackDomainEvent>()
        val emittedEvents = mutableListOf<PlaybackDomainEvent>()

        override val events: SharedFlow<PlaybackDomainEvent> = mutableEvents

        override fun emit(event: PlaybackDomainEvent): PlaybackDomainEventDeliveryResult {
            emittedEvents += event
            return PlaybackDomainEventDeliveryResult.Delivered(event)
        }
    }

    private class FakeLibraryRootDao(
        private var root: LibraryRootEntity?
    ) : LibraryRootDao {
        override fun getAllRoots(): Flow<List<LibraryRootEntity>> = flowOf(root?.let(::listOf).orEmpty())

        override suspend fun getActiveRootsOnce(): List<LibraryRootEntity> =
            root
                ?.takeIf { it.status == AudiobookSchema.LibraryRootStatus.ACTIVE }
                ?.let(::listOf)
                .orEmpty()

        override suspend fun getActiveAbsRootsOnce(): List<LibraryRootEntity> =
            root
                ?.takeIf(::isActiveAbsRoot)
                ?.let(::listOf)
                .orEmpty()

        override suspend fun getRootById(id: String): LibraryRootEntity? = root?.takeIf { it.id == id }

        override suspend fun getAllRootsOnce(): List<LibraryRootEntity> = root?.let(::listOf).orEmpty()

        override suspend fun insertRoot(root: LibraryRootEntity) {
            this.root = root
        }

        override suspend fun updateRootGrantState(id: String, displayName: String, grantedAt: Long, status: AudiobookSchema.LibraryRootStatus) = Unit

        override suspend fun updateRootScanState(id: String, lastScannedAt: Long, status: AudiobookSchema.LibraryRootStatus) = Unit

        override suspend fun updateRootStatus(id: String, status: AudiobookSchema.LibraryRootStatus) {
            root = root?.takeIf { it.id == id }?.copy(status = status) ?: root
        }

        override suspend fun updateRootAvailability(
            id: String,
            availabilityStatus: AudiobookSchema.AvailabilityStatus,
            checkedAt: Long,
            errorCode: String?
        ) = Unit

        override suspend fun deleteRoot(root: LibraryRootEntity) {
            if (this.root?.id == root.id) {
                this.root = null
            }
        }

        private fun isActiveAbsRoot(root: LibraryRootEntity): Boolean {
            return root.status == AudiobookSchema.LibraryRootStatus.ACTIVE &&
                root.sourceType == AudiobookSchema.LibrarySourceType.ABS
        }
    }

    private fun playbackPlan(rootId: String): BookPlaybackPlan =
        BookPlaybackPlan(
            bookId = BOOK_ID,
            title = "Resume Fixture",
            author = "Test Author",
            files = listOf(bookFile(rootId = rootId))
        )

    private fun bookFile(rootId: String): BookFileEntity =
        BookFileEntity(
            id = FILE_ID,
            bookId = BOOK_ID,
            rootId = rootId,
            index = 0,
            sourcePath = "chapter-1.mp3",
            sourceIdentity = "chapter-1.mp3",
            displayName = "Chapter 1",
            durationMs = 60_000L,
            fileSize = 1024L,
            lastModified = 1L
        )

    private fun libraryRoot(
        sourceType: AudiobookSchema.LibrarySourceType = AudiobookSchema.LibrarySourceType.SAF,
        sourceUri: String = "content://library-root",
        status: AudiobookSchema.LibraryRootStatus = AudiobookSchema.LibraryRootStatus.ACTIVE
    ): LibraryRootEntity =
        LibraryRootEntity(
            id = ROOT_ID,
            sourceType = sourceType,
            sourceUri = sourceUri,
            displayName = ROOT_NAME,
            status = status
        )

    private companion object {
        const val BOOK_ID = "book-1"
        const val FILE_ID = "file-1"
        const val ROOT_ID = "root-1"
        const val ROOT_NAME = "Detached Library"
    }
}
