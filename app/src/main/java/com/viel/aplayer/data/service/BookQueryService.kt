package com.viel.aplayer.data.service

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.BookmarkDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.BookmarkGateway
import com.viel.aplayer.data.gateway.ChapterGateway
import com.viel.aplayer.timeline.PositionMapper
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Audiobook Query and Maintenance Service (Implements split book gateways)
 * 
 * Core Design Goals:
 * 1. Domain Decoupling: Fully disconnects from BookLibraryRepository in the M6 phase, injecting fine-grained Room DAOs and file resolvers directly.
 * 2. Preserved Execution Semantics: Safely retains timing logs, bookmark calculations, and cover self-healing triggers during reactive queries.
 */
@OptIn(UnstableApi::class)
class BookQueryService(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val coverRecoveryHelper: CoverRecoveryHelper
) : BookCatalogGateway,
    BookMetadataGateway,
    BookmarkGateway,
    ChapterGateway,
    BookDeletionGateway,
    java.io.Closeable {

    // Private Coroutine Exception Handler (Asynchronous tracking fault barrier)
    // Captures failures during async metadata updates to prevent uncaught scope terminations.
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("BookQueryService", "协程在 BookQueryService 运行中捕获到未处理异常", exception)
    }

    // Private Service Coroutine Scope (Background operations thread pool)
    // Dedicated scope on Dispatchers.IO for offloading async metadata updates and non-blocking write tasks.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // Reactive Cover Self-Healing Interceptor (Flow thread redirection guard)
    // Dispatches file existence checks to Dispatchers.IO to prevent blocking the UI main thread.
    // Shields active render pipelines from ANR and frame drop exceptions during high-frequency scans.
    @OptIn(UnstableApi::class)
    private fun Flow<List<BookWithProgress>>.checkCovers(): Flow<List<BookWithProgress>> = this.map { list ->
        list.onEach { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it.book) }
    }.flowOn(Dispatchers.IO)

    // Position Anchor Data Container (Bookmark mapping tuple)
    // Temporarily stores file identifier, absolute milliseconds offset, file fingerprint, and resolve status.
    private data class Quad(
        val bookFileId: String?,
        val fileOffsetMs: Long,
        val fingerprint: String?,
        val anchorStatus: String
    )

    override val audiobooks: Flow<List<BookWithProgress>>
        get() = bookDao.getAllBooksWithProgress().checkCovers()

    override suspend fun getBookById(id: String): BookEntity? = withContext(Dispatchers.IO) {
        // Non-blocking cover repair check: Evaluates cover paths upon detail fetch and triggers extraction asynchronously.
        bookDao.getBookById(id)?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
    }

    override fun observeBookById(id: String): Flow<BookEntity?> {
        // Redundant IO Thread Isolation (Sanity read constraint)
        // Binds details observe flows to Dispatchers.IO to insulate the UI collector thread from blocking filesystem probes.
        return bookDao.observeBookById(id).map { book ->
            book?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
        }.flowOn(Dispatchers.IO)
    }

    override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> {
        return bookDao.searchBooksWithProgress(query).checkCovers()
    }

    override fun filterByYear(year: String): Flow<List<BookWithProgress>> {
        return bookDao.filterByYearWithProgress(year).checkCovers()
    }

    override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> {
        return bookDao.filterByAuthorWithProgress(author).checkCovers()
    }

    override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> {
        return bookDao.filterByAuthorLimitedWithProgress(author, excludeId, limit).checkCovers()
    }

    override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> {
        return bookDao.filterByNarratorWithProgress(narrator).checkCovers()
    }

    override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> {
        return bookDao.filterByNarratorLimitedWithProgress(narrator, excludeId, limit).checkCovers()
    }

    override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> {
        return bookDao.getRecentlyAddedWithProgress(limit).checkCovers()
    }

    override fun getRecentlyAddedExclusive(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>> {
        return bookDao.getRecentlyAddedExclusiveWithProgress(currentId, authors, narrators, limit).checkCovers()
    }

    override suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId)
        if (book != null) {
            // Logical Soft Delete (Identity preservation)
            // Overwrites status flags to DELETED rather than erasing records to prevent duplication during rescans.
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.DELETED)
        }
    }

    override suspend fun updateBookReadStatus(bookId: String, readStatus: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookReadStatus(bookId, readStatus)
    }

    // Metadata Update Implementation (Implements update details with series field support)
    // Forwards the series field to the BookDao to write changes to SQLite.
    override suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        series: String
    ) = withContext(Dispatchers.IO) {
        bookDao.updateBookDetails(id, title, author, narrator, description, year, series)
    }

    override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getAllFilesForBookList(bookId)
    }

    override fun updateMetadata(
        bookId: String,
        title: String?,
        author: String?,
        narrator: String?,
        description: String?,
        duration: Long
    ) {
        // Background tag updates: Persists metadata updates in the private IO coroutine scope.
        scope.launch {
            val existing = bookDao.getBookById(bookId) ?: return@launch
            val newTitle = if (!title.isNullOrBlank()) title else existing.title
            val newAuthor = if (!author.isNullOrBlank()) author else existing.author
            val newNarrator = if (!narrator.isNullOrBlank()) narrator else existing.narrator
            val newDescription = if (!description.isNullOrBlank()) description else existing.description
            val newDuration = if (duration > 0) duration else existing.totalDurationMs
            
            if (newTitle != existing.title || newAuthor != existing.author || 
                newNarrator != existing.narrator || newDescription != existing.description ||
                newDuration != existing.totalDurationMs) {
                bookDao.updateMetadata(bookId, newTitle, newAuthor, newNarrator, newDescription, newDuration)
            }
        }
    }

    override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> {
        // Query-Time Virtual Chapters Projection (Database data sanity protection)
        // Synthesizes a virtual single-track chapter dynamically when database chapter schemas are empty.
        // Shares identical chapter layouts across notification sessions and UI screens without persisting dummy data.
        return combine(
            chapterDao.getChaptersForBook(bookId),
            bookDao.observeBookById(bookId),
            bookDao.getFilesForBook(bookId)
        ) { chapters, book, files ->
            projectChaptersWithTrackFallback(book, files, chapters)
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = withContext(Dispatchers.IO) {
        // Unified Projection Rule Enforcement (Behavioral synchronization rule)
        // Shares identical projection mappings with synchronous queries to keep notifications and players aligned.
        val chapters = chapterDao.getChaptersForBookList(bookId)
        val book = bookDao.getBookById(bookId)
        val files = bookDao.getFilesForBookList(bookId)
        projectChaptersWithTrackFallback(book, files, chapters)
    }

    override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        // Database transaction block: Schedules chapter overrides asynchronously inside the private scope.
        scope.launch {
            if (bookDao.getBookById(bookId) != null) {
                // Chapter Insertion Transaction (ACID transaction enforcement)
                // Combines deletes and bulk inserts inside a single database transaction to prevent corrupt states.
                chapterDao.replaceChapters(bookId, chapters)
            }
        }
    }

    override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> {
        return bookmarkDao.getBookmarksForBook(bookId)
    }

    override suspend fun addBookmark(bookId: String, position: Long, title: String) = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        // Map Bookmark Coordinates (Sequence position mapping)
        // Computes relative track index and millisecond offset from global coordinates to write bookmark records accurately.
        val (bookFileId, fileOffsetMs, fingerprint, anchorStatus) = if (files.isNotEmpty()) {
            val (fileIndex, offset) = PositionMapper.globalToFilePosition(position, files)
            val file = files.getOrNull(fileIndex)
            Quad(file?.id, offset, file?.fingerprint, AudiobookSchema.AnchorStatus.OK)
        } else {
            Quad(null, 0L, null, AudiobookSchema.AnchorStatus.UNRESOLVED)
        }
        
        bookmarkDao.insert(BookmarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            globalPositionMs = position,
            bookFileId = bookFileId,
            fileOffsetMs = fileOffsetMs,
            fileFingerprint = fingerprint,
            anchorStatus = anchorStatus,
            title = title
        ))
        // Explicit return void: Forces matching with the gateway method signature.
        Unit
    }

    override suspend fun updateBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.insert(bookmark)
        // Explicit return void: Prevents returning Room insert IDs to the caller.
        Unit
    }

    override suspend fun deleteBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.delete(bookmark)
        // Explicit return void: Discards return signatures from Room delete calls.
        Unit
    }

    override fun close() {
        // Private Scope Teardown (Memory leak prevention)
        // Cancels the private scope upon service close to release background jobs and prevent memory leaks.
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
    // Skip override: Returns database chapters directly to prevent altering real parsed content.
    if (chapters.isNotEmpty()) {
        return chapters
    }
    // Sanity reference checks: Avoids synthesizing chapters if the target book does not exist in cache records.
    if (book == null) {
        return chapters
    }
    // Files existence check: Requires valid audio track mappings to execute projection loops.
    val sortedFiles = files.sortedBy { file -> file.index }
    if (sortedFiles.isEmpty()) {
        return chapters
    }
    var runningStartMs = 0L
    return sortedFiles.mapIndexed { trackIndex, file ->
        val safeDurationMs = when {
            file.durationMs > 0L -> file.durationMs
            // Duration Fallback: Recovers duration from global book records if individual track duration is missing.
            sortedFiles.size == 1 && book.totalDurationMs > 0L -> book.totalDurationMs
            else -> 0L
        }.coerceAtLeast(0L)
        val chapter = ChapterEntity(
            // Stable Synthetic ID: Computes a name-based UUID using bookId and fileId parameters.
            // Prevents Compose UI list flickers and notification track updates from unstable random values.
            id = syntheticTrackProjectionChapterId(book.id, file.id),
            bookId = book.id,
            // Primary Key Bindings: Associates the dynamic chapter with the real BookFileEntity ID.
            // Ensures seek commands and chapters completion checks map correctly onto physical file entities.
            bookFileId = file.id,
            index = trackIndex,
            title = projectedTrackChapterTitle(book, file, trackIndex, sortedFiles.size),
            startPositionMs = runningStartMs,
            durationMs = safeDurationMs,
            fileOffsetMs = 0L,
            source = AudiobookSchema.ChapterSource.GENERATED
        )
        // Accumulative Timelines: Increases relative start offsets with accumulated durations.
        // Maps multi-file tracks onto unified timeline structures across local and remote sources.
        runningStartMs += safeDurationMs
        ChapterWithBookFile(
            chapter = chapter,
            bookFile = file
        )
    }
}

/**
 * Stable Synthetic ID Generation (In-memory UUID builder)
 * Generates a stable name-based UUID for virtual track-chapter projection schemas.
 * Isolates in-memory structures to prevent primary key pollution in persistent tables.
 */
internal fun syntheticTrackProjectionChapterId(bookId: String, fileId: String): String =
    UUID.nameUUIDFromBytes("track-projection:$bookId:$fileId".toByteArray(StandardCharsets.UTF_8)).toString()

/**
 * Resolve Dynamic Chapter Title (Label generation priorities)
 * Prioritizes the book's title for single-file track representations, and individual track display names for multi-file systems.
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
