package com.viel.oto.media

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.cover.CoverRecoveryHelper
import com.viel.oto.data.cover.CoverUriResolver
import com.viel.oto.data.dao.BookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated playback startup materializer.
 *
 * Builds BookPlaybackPlan models from Room book, file, and progress rows without expanding the generic
 * BookCatalogGateway interface. This keeps playback startup logic local to a named playback-plan service.
 */
@OptIn(UnstableApi::class)
class PlaybackPlanGatewayImpl(
    private val coverUriResolver: CoverUriResolver,
    private val bookDao: BookDao,
    private val coverRecoveryHelper: CoverRecoveryHelper
) : PlaybackPlanGateway {
    @OptIn(UnstableApi::class)
    override suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan? = withContext(Dispatchers.IO) {
        val planBuildStart = SystemClock.elapsedRealtime()
        val bookQueryStart = SystemClock.elapsedRealtime()
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        val bookQueryCost = SystemClock.elapsedRealtime() - bookQueryStart

        coverRecoveryHelper.checkAndTriggerCoverRegeneration(book)

        val filesQueryStart = SystemClock.elapsedRealtime()
        val files = bookDao.getFilesForBookList(bookId)
        val filesQueryCost = SystemClock.elapsedRealtime() - filesQueryStart

        val progressQueryStart = SystemClock.elapsedRealtime()
        val progress = bookDao.getProgressForBookSync(bookId)
        val progressQueryCost = SystemClock.elapsedRealtime() - progressQueryStart

        if (files.isEmpty()) {
            val totalCost = SystemClock.elapsedRealtime() - planBuildStart
            com.viel.oto.logger.LibraryLogger.logPlaybackPlanEmpty(
                bookId = bookId,
                bookQueryMs = bookQueryCost,
                filesQueryMs = filesQueryCost,
                progressQueryMs = progressQueryCost,
                totalMs = totalCost
            )
            return@withContext null
        }

        val artworkPath = book.coverPath
        val artworkUri = if (!artworkPath.isNullOrBlank()) {
            coverUriResolver.toContentUri(artworkPath)?.toUri()
        } else {
            null
        }

        val plan = BookPlaybackPlan(
            bookId = bookId,
            title = book.title,
            author = book.author,
            artworkUri = artworkUri,
            files = files,
            subtitlesByFileId = emptyMap(),
            startGlobalPositionMs = progress?.globalPositionMs ?: 0L
        )
        val totalCost = SystemClock.elapsedRealtime() - planBuildStart
        com.viel.oto.logger.LibraryLogger.logPlaybackPlanReady(
            bookId = bookId,
            bookQueryMs = bookQueryCost,
            filesQueryMs = filesQueryCost,
            progressQueryMs = progressQueryCost,
            totalMs = totalCost,
            fileCount = plan.files.size,
            startPosition = plan.startGlobalPositionMs
        )
        plan
    }
}
