package com.viel.oto.application.usecase

import android.net.Uri
import com.viel.oto.application.download.BookCacheState
import com.viel.oto.application.download.BookCacheStatus
import com.viel.oto.application.download.DownloadStatusReadModel
import com.viel.oto.application.playback.PlaybackMediaIdentity
import com.viel.oto.application.playback.PlayerPlaybackController
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.media.BookPlaybackPlan
import com.viel.oto.media.PlaybackPlanGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareBookPlaybackUseCaseTest {

    @Test
    fun `invoke summarizes the built plan and loads it into the controller`() = runBlocking {
        val plan = testPlan(
            startGlobalPositionMs = 4_200L,
            files = listOf(testFile("file-1"), testFile("file-2"), testFile("file-3"))
        )
        val controller = RecordingPlayerPlaybackController()
        val useCase = PrepareBookPlaybackUseCase(
            buildPlaybackPlanUseCase = buildPlaybackPlanUseCase(plan),
            playbackController = controller
        )

        val summary = useCase(bookId = BOOK_ID, playWhenReady = true)

        assertEquals(BOOK_ID, summary?.bookId)
        assertEquals(3, summary?.fileCount)
        assertEquals(4_200L, summary?.startGlobalPositionMs)
        // The plan must be handed to the controller exactly once with the requested play-when-ready flag.
        assertEquals(1, controller.loadedPlans.size)
        assertEquals(plan, controller.loadedPlans.single())
        assertEquals(listOf(true), controller.loadWhenReadyFlags)
    }

    @Test
    fun `invoke forwards the playWhenReady false flag to the controller`() = runBlocking {
        val controller = RecordingPlayerPlaybackController()
        val useCase = PrepareBookPlaybackUseCase(
            buildPlaybackPlanUseCase = buildPlaybackPlanUseCase(testPlan()),
            playbackController = controller
        )

        useCase(bookId = BOOK_ID, playWhenReady = false)

        assertEquals(listOf(false), controller.loadWhenReadyFlags)
    }

    @Test
    fun `invoke reports zero files for an empty plan`() = runBlocking {
        val plan = testPlan(startGlobalPositionMs = 0L, files = emptyList())
        val controller = RecordingPlayerPlaybackController()
        val useCase = PrepareBookPlaybackUseCase(
            buildPlaybackPlanUseCase = buildPlaybackPlanUseCase(plan),
            playbackController = controller
        )

        val summary = useCase(bookId = BOOK_ID, playWhenReady = true)

        assertEquals(0, summary?.fileCount)
        assertEquals(0L, summary?.startGlobalPositionMs)
    }

    @Test
    fun `invoke returns null and skips the controller when no plan can be built`() = runBlocking {
        val controller = RecordingPlayerPlaybackController()
        val useCase = PrepareBookPlaybackUseCase(
            buildPlaybackPlanUseCase = buildPlaybackPlanUseCase(plan = null),
            playbackController = controller
        )

        val summary = useCase(bookId = BOOK_ID, playWhenReady = true)

        assertNull(summary)
        assertTrue(controller.loadedPlans.isEmpty())
        assertFalse(controller.loaded)
    }

    private fun buildPlaybackPlanUseCase(plan: BookPlaybackPlan?): BuildPlaybackPlanUseCase =
        BuildPlaybackPlanUseCase(
            playbackPlanGateway = StaticPlaybackPlanGateway(plan),
            downloadStatusReadModel = StaticDownloadStatusReadModel(BookCacheStatus.none())
        )

    private class StaticPlaybackPlanGateway(
        private val plan: BookPlaybackPlan?
    ) : PlaybackPlanGateway {
        override suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan? = plan
    }

    private class StaticDownloadStatusReadModel(
        private val cacheStatus: BookCacheStatus
    ) : DownloadStatusReadModel {
        override fun observeBookCacheStatus(bookId: String): Flow<BookCacheStatus> = flowOf(cacheStatus)
    }

    /**
     * Captures only the loadPlaybackPlan calls; every other controller member is an inert stub
     * because the use case under test never touches playback state, position, or speed.
     */
    private class RecordingPlayerPlaybackController : PlayerPlaybackController {
        val loadedPlans = mutableListOf<BookPlaybackPlan>()
        val loadWhenReadyFlags = mutableListOf<Boolean>()
        val loaded: Boolean get() = loadedPlans.isNotEmpty()

        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override val playbackState: StateFlow<Int> = MutableStateFlow(0)
        override val currentPosition: StateFlow<Long> = MutableStateFlow(0L)
        override val bufferedPosition: StateFlow<Long> = MutableStateFlow(0L)
        override val duration: StateFlow<Long> = MutableStateFlow(0L)
        override val playbackSpeed: StateFlow<Float> = MutableStateFlow(1.0f)
        override val currentPlaybackMediaIdentity: PlaybackMediaIdentity? = null
        override var playerVolume: Float = 1.0f

        override fun observeCurrentPlaybackMediaIdentity(): Flow<PlaybackMediaIdentity?> =
            flowOf(null)

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit

        override fun loadPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean) {
            loadedPlans += plan
            loadWhenReadyFlags += playWhenReady
        }

        override suspend fun performColdStartSelfHealing() = Unit
        override fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) = Unit
    }

    private companion object {
        private const val BOOK_ID = "book-1"

        private fun testPlan(
            startGlobalPositionMs: Long = 0L,
            files: List<BookFileEntity> = listOf(testFile("file-1"))
        ): BookPlaybackPlan =
            BookPlaybackPlan(
                bookId = BOOK_ID,
                title = "Book",
                author = "Author",
                artworkUri = Uri.EMPTY,
                files = files,
                startGlobalPositionMs = startGlobalPositionMs
            )

        private fun testFile(id: String): BookFileEntity =
            BookFileEntity(
                id = id,
                bookId = BOOK_ID,
                rootId = "root-1",
                index = 0,
                sourcePath = "chapter.mp3",
                sourceIdentity = "chapter.mp3",
                displayName = "chapter.mp3",
                durationMs = 1_000L,
                fileSize = 1_024L,
                lastModified = 0L
            )
    }
}
