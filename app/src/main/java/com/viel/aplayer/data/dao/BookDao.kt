package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.timeline.PositionMapper
import kotlinx.coroutines.flow.Flow

/**
 * Book Cover Cache Paths Projection (Reads only cover file coordinates for root cache eviction)
 * Keeps root-deletion cleanup from loading full BookEntity rows when it only needs sandboxed cover and thumbnail paths.
 */
data class BookCoverCachePaths(
    val coverPath: String?,
    val thumbnailPath: String?
)

@Dao
interface BookDao {
    // UI lists hide soft-deleted books while their BookFile claims stay reserved.
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY title ASC")
    fun getAllBooks(): Flow<List<BookEntity>>

    // Direct Snapshot Query (Suspended query to retrieve active books in a single shot for sync task initialization)
    // This avoids consuming Flow data streams when only a state check is needed.
    @Query("SELECT * FROM books WHERE status != 'DELETED'")
    suspend fun getAllBooksOnce(): List<BookEntity>

    // UI lists hide soft-deleted books while their BookFile claims stay reserved.
    @Transaction
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY title ASC")
    fun getAllBooksWithProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBookById(id: String): Flow<BookEntity?>

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateBookStatus(id: String, status: String)

    @Query("UPDATE books SET lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateBookLastScannedAt(id: String, lastScannedAt: Long)

    // Rescan builds ExistingClaimIndex from all persisted file ownership rows.
    @Query("SELECT * FROM book_files")
    suspend fun getAllBookFilesOnce(): List<BookFileEntity>

    // Cold-start light scans only re-check previously missing audio rows, not every old file.
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

    // Remove updateBackgroundColor (Deprecate database cover color writes) Clean SQL queries to align with field removal.

    @Transaction
    @Query("""
        SELECT * FROM books
        WHERE status != 'DELETED'
        AND (
            (title LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (title = '' OR title IS NULL)))
            OR (author LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (author = '' OR author IS NULL)))
            OR (narrator LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        )
        ORDER BY title ASC
    """)
    fun searchBooksWithProgress(query: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (year LIKE '%' || :year || '%' OR (:year = 'Unknown' AND (year = '' OR year IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByYearWithProgress(year: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByAuthorWithProgress(author: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByNarratorWithProgress(narrator: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        AND id != :excludeId
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun filterByAuthorLimitedWithProgress(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        AND id != :excludeId
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun filterByNarratorLimitedWithProgress(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    // Recently added UI excludes soft-deleted books.
    @Transaction
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY addedAt DESC LIMIT :limit")
    fun getRecentlyAddedWithProgress(limit: Int): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND id != :currentId
        AND author NOT IN (:authors) 
        AND narrator NOT IN (:narrators) 
        ORDER BY addedAt DESC LIMIT :limit
    """)
    fun getRecentlyAddedExclusiveWithProgress(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>>

    // BookFile rows include both SOURCE_MANIFEST and AUDIO ownership facts.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookFiles(files: List<BookFileEntity>)

    // Playback-facing file flow must exclude SOURCE_MANIFEST rows.
    @Query("SELECT * FROM book_files WHERE bookId = :bookId AND fileRole = 'AUDIO' ORDER BY `index` ASC")
    fun getFilesForBook(bookId: String): Flow<List<BookFileEntity>>

    // Playback and progress mapping use only AUDIO files.
    @Query("SELECT * FROM book_files WHERE bookId = :bookId AND fileRole = 'AUDIO' ORDER BY `index` ASC")
    suspend fun getFilesForBookList(bookId: String): List<BookFileEntity>

    // Complete File Listing (Retrieves all physical book files including media tracks and manifest records)
    // Useful on detail screens to inspect actual file configurations.
    @Query("SELECT * FROM book_files WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getAllFilesForBookList(bookId: String): List<BookFileEntity>

    // Stable ID Resolution (Resolves the associated BookFileEntity via its unique ID as exposed in MediaItem.mediaId)
    // Keeps playback and subtitle layers isolated from raw file URI queries.
    @Query("SELECT * FROM book_files WHERE id = :id AND fileRole = 'AUDIO' LIMIT 1")
    suspend fun getBookFileById(id: String): BookFileEntity?

    @Query("UPDATE book_files SET status = :status, lastSeenScanId = :scanId WHERE id = :id")
    suspend fun updateBookFileStatus(id: String, status: String, scanId: String? = null)

    // Batch Status Updates (Updates multiple file reachability flags inside a single SQL IN transaction)
    // Prevents repetitive database writes from lagging UI threads during library verification.
    @Query("UPDATE book_files SET status = :status, lastSeenScanId = :scanId WHERE id IN (:ids)")
    suspend fun updateBookFileStatuses(ids: List<String>, status: String, scanId: String? = null)

    // BookProgress is created only when playback/seek/save actually happens.
    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    fun getProgressForBook(bookId: String): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    suspend fun getProgressForBookSync(bookId: String): BookProgressEntity?

    // Cold Start Session Healing (Queries progress of the last played book that is incomplete (progress < 99%))
    // Prevents completed audiobooks from being restored into the miniplayer during cold startup loops.
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

    // Save Metadata Edits (Updates logical book details including title, creator, narrator, release year, and series name)
    // Updates the series field along with other editable metadata in books table.
    @Query("UPDATE books SET title = :title, author = :author, narrator = :narrator, description = :description, year = :year, series = :series WHERE id = :id")
    suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String)

    // Partial Cover Path Resolution (Re-persists cover location and scan timestamps after cache clearance)
    // Updates specific columns to prevent overwrite race conditions and triggers Flow emissions for UI redraws.
    @Query("UPDATE books SET coverPath = :coverPath, thumbnailPath = :thumbnailPath, lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateCoverPaths(id: String, coverPath: String?, thumbnailPath: String?, lastScannedAt: Long)

    // Root Directory Teardown Helper (Retrieves books associated with a target root ID to safely delete cached cover files)
    // This avoids piling up orphaned thumbnail files in sandboxed application caches.
    @Query("SELECT * FROM books WHERE rootId = :rootId")
    suspend fun getBooksByRootId(rootId: String): List<BookEntity>

    // Root Cover Cache Projection (Retrieves only cache file paths belonging to one root)
    // Used by CacheEvictionCoordinator before root deletion so cleanup avoids reading metadata, progress, or file ownership columns.
    @Query("SELECT coverPath, thumbnailPath FROM books WHERE rootId = :rootId")
    suspend fun getCoverCachePathsByRootId(rootId: String): List<BookCoverCachePaths>

    @Query("UPDATE books SET readStatus = :readStatus WHERE id = :id")
    suspend fun updateBookReadStatus(id: String, readStatus: String)

    /**
     * Atomic Progress Transaction (Atomically executes reading, mapping, inserting, and readStatus updates within a transaction)
     * Utilizes Room's @Transaction mechanism to combine multi-step writes into a single database transaction.
     * This avoids read-modify-write race conditions under concurrent progress reports and maintains database consistency.
     */
    @Transaction
    suspend fun updateProgressWithReadStatus(bookId: String, position: Long, currentTime: Long) {
        val progress = getProgressForBookSync(bookId)
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

        // Read State Correlation (Dynamically transition readStatus flags based on calculated position progress)
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
    }
}
