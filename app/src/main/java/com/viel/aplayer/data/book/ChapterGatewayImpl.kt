package com.viel.aplayer.data.book

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.logger.SecureLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Chapter Service (Implements ChapterGateway)
 *
 * Owns chapter timeline reads and persistence. Both reads project a virtual single-track chapter when the
 * database has no parsed chapters (see [projectChaptersWithTrackFallback]). [saveChapters] is fire-and-forget
 * on a private IO scope, so this service is [Closeable] and must be torn down by the graph.
 */
@OptIn(UnstableApi::class)
class ChapterGatewayImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) : ChapterGateway, Closeable {

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        // Background chapter writes may carry physical paths, so release-retained errors go through SecureLog.
        SecureLog.error("ChapterGatewayImpl", "协程在 ChapterGatewayImpl 运行中捕获到未处理异常", exception)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> {
        return combine(
            chapterDao.getChaptersForBook(bookId),
            bookDao.observeBookById(bookId),
            bookDao.getFilesForBook(bookId)
        ) { chapters, book, files ->
            projectChaptersWithTrackFallback(book, files, chapters)
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = withContext(Dispatchers.IO) {
        // Same projection rule as the reactive flow so notifications and the player stay aligned.
        val chapters = chapterDao.getChaptersForBookList(bookId)
        val book = bookDao.getBookById(bookId)
        val files = bookDao.getFilesForBookList(bookId)
        projectChaptersWithTrackFallback(book, files, chapters)
    }

    override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        scope.launch {
            if (bookDao.getBookById(bookId) != null) {
                // Delete + bulk insert run inside a single Room transaction to prevent partial chapter state.
                chapterDao.replaceChapters(bookId, chapters)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Unified Query-Time Chapters Projection Rules (Dynamic fallback mapping logic)
 *
 * Rules:
 * 1. Preserves existing parsed database chapters; does not override authentic tags.
 * 2. Dynamically builds a single track fallback chapter if database chapter schemas are empty.
 * 3. Retains projection models strictly in-memory, avoiding persisting dummy entries to database tables.
 */
internal fun projectChaptersWithTrackFallback(
    book: BookEntity?,
    files: List<BookFileEntity>,
    chapters: List<ChapterWithBookFile>
): List<ChapterWithBookFile> {
    if (chapters.isNotEmpty()) {
        return chapters
    }
    if (book == null) {
        return chapters
    }
    val sortedFiles = files.sortedBy { file -> file.index }
    if (sortedFiles.isEmpty()) {
        return chapters
    }
    var runningStartMs = 0L
    return sortedFiles.mapIndexed { trackIndex, file ->
        val safeDurationMs = when {
            file.durationMs > 0L -> file.durationMs
            // Recover duration from the global book record when a single track is missing its own duration.
            sortedFiles.size == 1 && book.totalDurationMs > 0L -> book.totalDurationMs
            else -> 0L
        }.coerceAtLeast(0L)
        val chapter = ChapterEntity(
            // Stable name-based UUID keeps Compose lists and notification tracks from flickering on unstable ids.
            id = syntheticTrackProjectionChapterId(book.id, file.id),
            bookId = book.id,
            bookFileId = file.id,
            index = trackIndex,
            title = projectedTrackChapterTitle(book, file, trackIndex, sortedFiles.size),
            startPositionMs = runningStartMs,
            durationMs = safeDurationMs,
            fileOffsetMs = 0L,
            source = AudiobookSchema.ChapterSource.GENERATED
        )
        runningStartMs += safeDurationMs
        ChapterWithBookFile(
            chapter = chapter,
            bookFile = file
        )
    }
}

/**
 * Stable Synthetic ID Generation (In-memory UUID builder)
 * Generates a stable name-based UUID for virtual track-chapter projection schemas so in-memory rows never
 * pollute persistent primary keys.
 */
internal fun syntheticTrackProjectionChapterId(bookId: String, fileId: String): String =
    UUID.nameUUIDFromBytes("track-projection:$bookId:$fileId".toByteArray(StandardCharsets.UTF_8)).toString()

/**
 * Resolve Dynamic Chapter Title (Label generation priorities)
 * Prioritizes the book title for single-file tracks and individual track display names for multi-file books.
 */
internal fun projectedTrackChapterTitle(
    book: BookEntity,
    file: BookFileEntity,
    trackIndex: Int,
    totalTracks: Int
): String {
    val displayName = file.displayName.substringBeforeLast('.', file.displayName).ifBlank { file.displayName }
    return when {
        totalTracks == 1 && book.title.isNotBlank() -> book.title
        displayName.isNotBlank() -> displayName
        book.title.isNotBlank() -> "${book.title} ${trackIndex + 1}"
        else -> "Track ${trackIndex + 1}"
    }
}
