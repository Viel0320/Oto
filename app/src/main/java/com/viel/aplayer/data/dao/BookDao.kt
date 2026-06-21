package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.viel.aplayer.data.book.HomeCatalogRow
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.timeline.PositionMapper
import kotlinx.coroutines.flow.Flow

/**
 * Reads only cover file coordinates for root cache eviction.
 * Keeps root-deletion cleanup from loading full BookEntity rows when it only needs sandboxed cover and thumbnail paths.
 */
data class BookCoverCachePaths(
    val coverPath: String?,
    val thumbnailPath: String?
)

/**
 * Carries only list-row fields needed by the recovery scene.
 * Keeps the restore page from observing full Room entities while preserving cover cache busting and retained progress display.
 */
data class DeletedBookRecoveryProjection(
    val bookId: String,
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val coverPath: String?,
    val coverLastUpdated: Long,
    val progressPercent: Int,
    val sourceLabel: String
)

/**
 * Omits heavy text fields like description, generatedManifestJson, and heuristicRuleVersion to optimize query IO.
 */
data class BookMinEntity(
    val id: String,
    val rootId: String,
    val sourceType: AudiobookSchema.SourceType,
    val sourceRoot: String,
    val title: String,
    val author: String,
    val narrator: String,
    val year: String,
    val totalDurationMs: Long,
    val totalFileSize: Long,
    val coverPath: String?,
    val thumbnailPath: String?,
    val addedAt: Long,
    val lastScannedAt: Long,
    val status: AudiobookSchema.BookStatus,
    val readStatus: AudiobookSchema.ReadStatus,
    val series: String
) {
    fun toBookEntity() = BookEntity(
        id = id,
        rootId = rootId,
        sourceType = sourceType,
        sourceRoot = sourceRoot,
        title = title,
        author = author,
        narrator = narrator,
        description = "",
        year = year,
        totalDurationMs = totalDurationMs,
        totalFileSize = totalFileSize,
        coverPath = coverPath,
        thumbnailPath = thumbnailPath,
        addedAt = addedAt,
        lastScannedAt = lastScannedAt,
        status = status,
        readStatus = readStatus,
        series = series,
        generatedManifestJson = null,
        heuristicRuleVersion = null
    )
}

/**
 * Combines BookMinEntity with its BookProgressEntity.
 */
data class BookMinWithProgress(
    @Embedded val book: BookMinEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId"
    )
    val progress: BookProgressEntity?
) {
    fun toBookWithProgress() = BookWithProgress(
        book = book.toBookEntity(),
        progress = progress
    )
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY title ASC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE status != 'DELETED'")
    suspend fun getAllBooksOnce(): List<BookEntity>

    /**
     * Bounded startup self-heal snapshot.
     * Prioritizes books with missing stored artwork paths, then samples the oldest scanned books so cold-start recovery
     * no longer walks the whole catalog or performs unbounded cache-presence checks while Home is settling.
     */
    @Query("""
        SELECT * FROM books
        WHERE status != 'DELETED'
        ORDER BY
            CASE WHEN coverPath IS NULL OR thumbnailPath IS NULL THEN 0 ELSE 1 END,
            lastScannedAt ASC,
            addedAt DESC,
            title ASC
        LIMIT :limit
    """)
    suspend fun getCoverRecoveryCandidates(limit: Int): List<BookEntity>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series
        FROM books
        WHERE status != 'DELETED'
        ORDER BY title ASC
    """)
    fun getAllBooksWithProgress(): Flow<List<BookMinWithProgress>>

    /**
     * Reads the shelf fields and progress in one query.
     *
     * Avoids Room @Relation fan-out for the first-screen catalog by joining the single progress row directly and
     * computing Home's progress percentage in SQL. Heavy fields intentionally stay out of the projection.
     */
    @Query("""
        SELECT
            books.id AS id,
            books.rootId AS rootId,
            books.sourceType AS sourceType,
            books.status AS status,
            books.title AS title,
            books.author AS author,
            books.narrator AS narrator,
            books.year AS year,
            books.series AS series,
            books.totalDurationMs AS totalDurationMs,
            books.totalFileSize AS totalFileSize,
            books.coverPath AS coverPath,
            books.thumbnailPath AS thumbnailPath,
            books.lastScannedAt AS lastScannedAt,
            books.addedAt AS addedAt,
            books.readStatus AS readStatus,
            CASE
                WHEN books.totalDurationMs > 0 AND book_progress.globalPositionMs IS NOT NULL
                THEN MIN(100, MAX(0, CAST(((book_progress.globalPositionMs * 100) + books.totalDurationMs - 1) / books.totalDurationMs AS INTEGER)))
                ELSE 0
            END AS progressPercent,
            COALESCE(book_progress.lastPlayedAt, 0) AS lastPlayedAt
        FROM books
        LEFT JOIN book_progress ON book_progress.bookId = books.id
        WHERE books.status != 'DELETED'
        ORDER BY books.title ASC
    """)
    fun observeHomeCatalogRows(): Flow<List<HomeCatalogRow>>

    /**
     * Projects soft-deleted catalog rows for manual recovery.
     * Joins retained progress and root labels without exposing full entity graphs to the settings recovery page.
     */
    @Query("""
        SELECT
            books.id AS bookId,
            books.title AS title,
            books.author AS author,
            books.narrator AS narrator,
            books.totalDurationMs AS durationMs,
            books.coverPath AS coverPath,
            books.lastScannedAt AS coverLastUpdated,
            CASE
                WHEN books.totalDurationMs > 0 AND book_progress.globalPositionMs IS NOT NULL
                THEN CAST(((book_progress.globalPositionMs * 100) + books.totalDurationMs - 1) / books.totalDurationMs AS INTEGER)
                ELSE 0
            END AS progressPercent,
            COALESCE(NULLIF(library_roots.displayName, ''), library_roots.sourceType, books.sourceType) AS sourceLabel
        FROM books
        LEFT JOIN book_progress ON book_progress.bookId = books.id
        LEFT JOIN library_roots ON library_roots.id = books.rootId
        WHERE books.status = 'DELETED'
        ORDER BY books.title ASC
    """)
    fun observeDeletedBookRecoveryProjections(): Flow<List<DeletedBookRecoveryProjection>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBookById(id: String): Flow<BookEntity?>

    /**
     * Stream the protocol/provider type of a book's root library.
     * Detail presentation layers use this type-safe enum to check if the book is hosted on a local SAF provider,
     * allowing the UI to bypass manual cache actions for natively offline files.
     */
    @Query("""
        SELECT library_roots.sourceType
        FROM books
        INNER JOIN library_roots ON library_roots.id = books.rootId
        WHERE books.id = :bookId
    """)
    fun observeBookLibrarySourceType(bookId: String): Flow<AudiobookSchema.LibrarySourceType?>

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateBookStatus(id: String, status: AudiobookSchema.BookStatus)

    @Query("UPDATE books SET lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateBookLastScannedAt(id: String, lastScannedAt: Long)

    @Query("SELECT * FROM book_files")
    suspend fun getAllBookFilesOnce(): List<BookFileEntity>

    @Query("""
        SELECT book_files.* FROM book_files
        INNER JOIN books ON books.id = book_files.bookId
        WHERE books.status != 'DELETED'
        AND book_files.fileRole = 'AUDIO'
        AND book_files.status = 'MISSING'
        ORDER BY book_files.bookId ASC, book_files.`index` ASC
    """)
    suspend fun getMissingAudioBookFilesOnce(): List<BookFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series FROM books
        WHERE status != 'DELETED'
        AND (
            (title LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (title = '' OR title IS NULL)))
            OR (author LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (author = '' OR author IS NULL)))
            OR (narrator LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        )
        ORDER BY title ASC
    """)
    fun searchBooksWithProgress(query: String): Flow<List<BookMinWithProgress>>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series FROM books
        WHERE status != 'DELETED'
        AND (year LIKE '%' || :year || '%' OR (:year = 'Unknown' AND (year = '' OR year IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByYearWithProgress(year: String): Flow<List<BookMinWithProgress>>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series FROM books
        WHERE status != 'DELETED'
        AND (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByAuthorWithProgress(author: String): Flow<List<BookMinWithProgress>>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series FROM books
        WHERE status != 'DELETED'
        AND (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByNarratorWithProgress(narrator: String): Flow<List<BookMinWithProgress>>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series FROM books
        WHERE status != 'DELETED'
        AND (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        AND id != :excludeId
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun filterByAuthorLimitedWithProgress(author: String, excludeId: String, limit: Int): Flow<List<BookMinWithProgress>>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series FROM books
        WHERE status != 'DELETED'
        AND (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        AND id != :excludeId
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun filterByNarratorLimitedWithProgress(narrator: String, excludeId: String, limit: Int): Flow<List<BookMinWithProgress>>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series
        FROM books
        WHERE status != 'DELETED'
        ORDER BY addedAt DESC
        LIMIT :limit
    """)
    fun getRecentlyAddedWithProgress(limit: Int): Flow<List<BookMinWithProgress>>

    @Transaction
    @Query("""
        SELECT id, rootId, sourceType, sourceRoot, title, author, narrator, year,
               totalDurationMs, totalFileSize, coverPath, thumbnailPath, addedAt,
               lastScannedAt, status, readStatus, series FROM books
        WHERE status != 'DELETED'
        AND id != :currentId
        AND author NOT IN (:authors)
        AND narrator NOT IN (:narrators)
        ORDER BY addedAt DESC
        LIMIT :limit
    """)
    fun getRecentlyAddedExclusiveWithProgress(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookMinWithProgress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookFiles(files: List<BookFileEntity>)

    @Query("SELECT * FROM book_files WHERE bookId = :bookId AND fileRole = 'AUDIO' ORDER BY `index` ASC")
    fun getFilesForBook(bookId: String): Flow<List<BookFileEntity>>

    @Query("SELECT * FROM book_files WHERE bookId = :bookId AND fileRole = 'AUDIO' ORDER BY `index` ASC")
    suspend fun getFilesForBookList(bookId: String): List<BookFileEntity>

    @Query("SELECT * FROM book_files WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getAllFilesForBookList(bookId: String): List<BookFileEntity>

    @Query("SELECT * FROM book_files WHERE id = :id AND fileRole = 'AUDIO' LIMIT 1")
    suspend fun getBookFileById(id: String): BookFileEntity?

    @Query("SELECT bookId FROM book_files WHERE id = :fileId LIMIT 1")
    suspend fun getBookIdByFileId(fileId: String): String?

    @Query("UPDATE book_files SET status = :status, lastSeenScanId = :scanId WHERE id = :id")
    suspend fun updateBookFileStatus(id: String, status: AudiobookSchema.FileStatus, scanId: String? = null)

    @Query("UPDATE book_files SET status = :status, lastSeenScanId = :scanId WHERE id IN (:ids)")
    suspend fun updateBookFileStatuses(ids: List<String>, status: AudiobookSchema.FileStatus, scanId: String? = null)

    /**
     * Reactivates a still-deleted book and marks readable audio rows ready.
     * Returns false when the row no longer exists or has already left DELETED, preventing stale dialogs from rewriting current state.
     */
    @Transaction
    suspend fun restoreDeletedBookReady(bookId: String, readyFileIds: List<String>): Boolean {
        val book = getBookById(bookId)
        if (book?.status != AudiobookSchema.BookStatus.DELETED) return false
        updateBookStatus(bookId, AudiobookSchema.BookStatus.READY)
        if (readyFileIds.isNotEmpty()) {
            updateBookFileStatuses(readyFileIds, AudiobookSchema.FileStatus.READY)
        }
        return true
    }

    /**
     * Reactivates a still-deleted book with split READY and MISSING audio rows.
     * Preserves existing progress and metadata while committing the partial-playability decision atomically.
     */
    @Transaction
    suspend fun restoreDeletedBookPartial(
        bookId: String,
        readyFileIds: List<String>,
        missingFileIds: List<String>
    ): Boolean {
        val book = getBookById(bookId)
        if (book?.status != AudiobookSchema.BookStatus.DELETED) return false
        if (readyFileIds.isEmpty()) return false
        updateBookStatus(bookId, AudiobookSchema.BookStatus.PARTIAL)
        updateBookFileStatuses(readyFileIds, AudiobookSchema.FileStatus.READY)
        if (missingFileIds.isNotEmpty()) {
            updateBookFileStatuses(missingFileIds, AudiobookSchema.FileStatus.MISSING)
        }
        return true
    }

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    fun getProgressForBook(bookId: String): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    suspend fun getProgressForBookSync(bookId: String): BookProgressEntity?

    @Query("""
        SELECT book_progress.* FROM book_progress
        INNER JOIN books ON books.id = book_progress.bookId
        WHERE books.status != 'DELETED'
        AND (books.totalDurationMs = 0 OR book_progress.globalPositionMs < (books.totalDurationMs * 0.99))
        ORDER BY book_progress.lastPlayedAt DESC
        LIMIT 1
    """)
    suspend fun getLastPlayedProgressSync(): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: BookProgressEntity)

    @Query("UPDATE books SET title = :title, author = :author, narrator = :narrator, description = :description, totalDurationMs = :duration WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, author: String, narrator: String, description: String, duration: Long)

    @Query("UPDATE books SET title = :title, author = :author, narrator = :narrator, description = :description, year = :year, series = :series WHERE id = :id")
    suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String)

    @Query("UPDATE books SET coverPath = :coverPath, thumbnailPath = :thumbnailPath, lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateCoverPaths(id: String, coverPath: String?, thumbnailPath: String?, lastScannedAt: Long)

    @Query("SELECT * FROM books WHERE rootId = :rootId")
    suspend fun getBooksByRootId(rootId: String): List<BookEntity>

    /**
     * Collects download cleanup targets before cascade deletion.
     * Root-management use cases need only stable book ids for manual-cache cleanup, so this query avoids loading cover,
     * metadata, and progress-facing columns.
     */
    @Query("SELECT id FROM books WHERE rootId = :rootId")
    suspend fun getBookIdsByRootId(rootId: String): List<String>

    /**
     * Force wipes all book rows belonging to a root directory.
     * Cascades down to physical audio records and chapter segments automatically.
     */
    @Query("DELETE FROM books WHERE rootId = :rootId")
    suspend fun deleteBooksByRootId(rootId: String)

    @Query("SELECT coverPath, thumbnailPath FROM books WHERE rootId = :rootId")
    suspend fun getCoverCachePathsByRootId(rootId: String): List<BookCoverCachePaths>

    /**
     * Retrieves only one book's cache file paths.
     * Book-management cleanup runs before soft deletion, so it reads the artwork paths while the row is still active enough
     * to identify its owned cached files.
     */
    @Query("SELECT coverPath, thumbnailPath FROM books WHERE id = :bookId")
    suspend fun getCoverCachePathsByBookId(bookId: String): BookCoverCachePaths?

    @Query("UPDATE books SET readStatus = :readStatus WHERE id = :id")
    suspend fun updateBookReadStatus(id: String, readStatus: AudiobookSchema.ReadStatus)

    /**
     * Atomically executes reading, mapping, inserting, and readStatus updates within a transaction.
     * Utilizes Room's @Transaction mechanism to combine multi-step writes into a single database transaction.
     * This avoids read-modify-write race conditions under concurrent progress reports and maintains database consistency.
     */
    @Transaction
    suspend fun updateProgressWithReadStatus(bookId: String, position: Long, currentTime: Long): Boolean {
        val progress = getProgressForBookSync(bookId)
        if (progress != null && currentTime < progress.lastPlayedAt) {
            return false
        }
        val files = getFilesForBookList(bookId)

        if (files.isNotEmpty()) {
            val (fileIndex, posInFile) = PositionMapper.globalToFilePosition(position, files)
            val bookFileId = files.getOrNull(fileIndex)?.id

            val updated = progress?.copy(
                globalPositionMs = position,
                bookFileId = bookFileId,
                currentFileIndex = fileIndex,
                positionInFileMs = posInFile,
                lastPlayedAt = currentTime
            ) ?: BookProgressEntity(
                bookId = bookId,
                globalPositionMs = position,
                bookFileId = bookFileId,
                currentFileIndex = fileIndex,
                positionInFileMs = posInFile,
                anchorStatus = AudiobookSchema.AnchorStatus.OK,
                lastPlayedAt = currentTime
            )
            insertProgress(updated)
        } else if (progress != null) {
            insertProgress(progress.copy(
                globalPositionMs = position,
                lastPlayedAt = currentTime
            ))
        }

        val book = getBookById(bookId)
        if (book != null) {
            val nextStatus = when {
                book.totalDurationMs > 0L && position >= (book.totalDurationMs * 0.99).toLong() -> AudiobookSchema.ReadStatus.FINISHED
                position > 0L -> AudiobookSchema.ReadStatus.IN_PROGRESS
                else -> AudiobookSchema.ReadStatus.NOT_STARTED
            }
            if (book.readStatus != nextStatus) {
                updateBookReadStatus(bookId, nextStatus)
            }
        }
        return true
    }
}
