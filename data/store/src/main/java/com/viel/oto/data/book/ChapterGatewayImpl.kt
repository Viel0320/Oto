package com.viel.oto.data.book

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.ChapterDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.ChapterWithBookFile
import com.viel.oto.logger.DiagnosticLogSink
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
 * Implements ChapterGateway.
 *
 * Owns chapter timeline reads and persistence. Both reads project a virtual single-track chapter when the
 * database has no parsed chapters (see [projectChaptersWithTrackFallback]). [saveChapters] is fire-and-forget
 * on a private IO scope, so this service is [Closeable] and must be torn down by the graph.
 */
class ChapterGatewayImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val diagnosticLogSink: DiagnosticLogSink
) : ChapterGateway, Closeable {

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        diagnosticLogSink.error("ChapterGatewayImpl", "协程在 ChapterGatewayImpl 运行中捕获到未处理异常", exception)
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
        val chapters = chapterDao.getChaptersForBookList(bookId)
        val book = bookDao.getBookById(bookId)
        val files = bookDao.getFilesForBookList(bookId)
        projectChaptersWithTrackFallback(book, files, chapters)
    }

    override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        scope.launch {
            if (bookDao.getBookById(bookId) != null) {
                chapterDao.replaceChapters(bookId, chapters)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Dynamic fallback mapping logic.
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
            sortedFiles.size == 1 && book.totalDurationMs > 0L -> book.totalDurationMs
            else -> 0L
        }.coerceAtLeast(0L)
        val chapter = ChapterEntity(
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
 * In-memory UUID builder.
 * Generates a stable name-based UUID for virtual track-chapter projection schemas so in-memory rows never
 * pollute persistent primary keys.
 */
internal fun syntheticTrackProjectionChapterId(bookId: String, fileId: String): String =
    UUID.nameUUIDFromBytes("track-projection:$bookId:$fileId".toByteArray(StandardCharsets.UTF_8)).toString()

/**
 * Label generation priorities.
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
