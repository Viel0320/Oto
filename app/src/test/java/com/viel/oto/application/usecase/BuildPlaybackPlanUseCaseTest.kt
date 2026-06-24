package com.viel.oto.application.usecase

import android.net.Uri
import com.viel.oto.application.download.BookCacheState
import com.viel.oto.application.download.BookCacheStatus
import com.viel.oto.application.download.DownloadStatusReadModel
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.media.BookPlaybackPlan
import com.viel.oto.media.PlaybackBufferPolicy
import com.viel.oto.media.PlaybackPlanGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildPlaybackPlanUseCaseTest {
    @Test
    fun `local cache status should request direct playback buffer policy`() = runBlocking {
        val useCase = createUseCase(BookCacheStatus.local())

        val plan = useCase(BOOK_ID)

        assertEquals(PlaybackBufferPolicy.Direct, plan?.bufferPolicy)
    }

    @Test
    fun `completed cache status should request direct playback buffer policy`() = runBlocking {
        val useCase = createUseCase(cacheStatus(BookCacheState.COMPLETED))

        val plan = useCase(BOOK_ID)

        assertEquals(PlaybackBufferPolicy.Direct, plan?.bufferPolicy)
    }

    @Test
    fun `uncached status should keep streaming playback buffer policy`() = runBlocking {
        val useCase = createUseCase(BookCacheStatus.none())

        val plan = useCase(BOOK_ID)

        assertEquals(PlaybackBufferPolicy.Buffered, plan?.bufferPolicy)
    }

    private fun createUseCase(cacheStatus: BookCacheStatus): BuildPlaybackPlanUseCase =
        BuildPlaybackPlanUseCase(
            playbackPlanGateway = StaticPlaybackPlanGateway(testPlan()),
            downloadStatusReadModel = StaticDownloadStatusReadModel(cacheStatus)
        )

    private class StaticPlaybackPlanGateway(
        private val plan: BookPlaybackPlan
    ) : PlaybackPlanGateway {
        override suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan = plan
    }

    private class StaticDownloadStatusReadModel(
        private val cacheStatus: BookCacheStatus
    ) : DownloadStatusReadModel {
        override fun observeBookCacheStatus(bookId: String): Flow<BookCacheStatus> = flowOf(cacheStatus)
    }

    private companion object {
        private const val BOOK_ID = "book-1"

        private fun cacheStatus(state: BookCacheState): BookCacheStatus =
            BookCacheStatus(
                state = state,
                totalFiles = 1,
                completedFiles = if (state == BookCacheState.COMPLETED) 1 else 0,
                totalBytes = 1024L,
                downloadedBytes = if (state == BookCacheState.COMPLETED) 1024L else 0L
            )

        /**
         * Provides the smallest valid playback plan so the test focuses only on cache-status mapping.
         */
        private fun testPlan(): BookPlaybackPlan =
            BookPlaybackPlan(
                bookId = BOOK_ID,
                title = "Book",
                author = "Author",
                artworkUri = Uri.EMPTY,
                files = listOf(testFile())
            )

        private fun testFile(): BookFileEntity =
            BookFileEntity(
                id = "file-1",
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
