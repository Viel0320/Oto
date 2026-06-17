package com.viel.aplayer.media

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.cover.CoverUriResolver
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playback Plan Service (Dedicated playback startup materializer)
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
        // Playback Plan Timing Boundary (Measure DAO reads as one playback-start operation)
        // The logger keeps the previous latency visibility while the implementation now lives outside BookQueryService.
        val planBuildStart = SystemClock.elapsedRealtime()
        val bookQueryStart = SystemClock.elapsedRealtime()
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        val bookQueryCost = SystemClock.elapsedRealtime() - bookQueryStart

        // Playback Cover Self-Healing (Preserve artwork repair side effect during plan creation)
        // Playback startup still opportunistically validates cached covers so player artwork can recover without a separate UI query.
        coverRecoveryHelper.checkAndTriggerCoverRegeneration(book)

        val filesQueryStart = SystemClock.elapsedRealtime()
        val files = bookDao.getFilesForBookList(bookId)
        val filesQueryCost = SystemClock.elapsedRealtime() - filesQueryStart

        val progressQueryStart = SystemClock.elapsedRealtime()
        val progress = bookDao.getProgressForBookSync(bookId)
        val progressQueryCost = SystemClock.elapsedRealtime() - progressQueryStart

        if (files.isEmpty()) {
            val totalCost = SystemClock.elapsedRealtime() - planBuildStart
            com.viel.aplayer.logger.LibraryLogger.logPlaybackPlanEmpty(
                bookId = bookId,
                bookQueryMs = bookQueryCost,
                filesQueryMs = filesQueryCost,
                progressQueryMs = progressQueryCost,
                totalMs = totalCost
            )
            return@withContext null
        }

        val artworkPath = book.coverPath
        // Artwork URI Resolution (Translate stored cover paths for Media3 consumers)
        // Keeping resolver injection here prevents playback callers from knowing Android content URI details.
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
        com.viel.aplayer.logger.LibraryLogger.logPlaybackPlanReady(
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
