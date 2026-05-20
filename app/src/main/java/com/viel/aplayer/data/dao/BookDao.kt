package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress

@Dao
interface BookDao {
    // UI lists hide soft-deleted books while their BookFile claims stay reserved.
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY addedAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    // UI lists hide soft-deleted books while their BookFile claims stay reserved.
    @Transaction
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY addedAt DESC")
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

    @Query("UPDATE books SET backgroundColorArgb = :color WHERE id = :id")
    suspend fun updateBackgroundColor(id: String, color: Int)

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

    // Subtitle loading may start from MediaItem.uri, so map it back to the scanned BookFile row first.
    @Query("SELECT * FROM book_files WHERE uri = :uri LIMIT 1")
    suspend fun getBookFileByUri(uri: String): BookFileEntity?

    @Query("UPDATE book_files SET status = :status, lastSeenScanId = :scanId WHERE id = :id")
    suspend fun updateBookFileStatus(id: String, status: String, scanId: String? = null)

    // BookProgress is created only when playback/seek/save actually happens.
    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    fun getProgressForBook(bookId: String): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    suspend fun getProgressForBookSync(bookId: String): BookProgressEntity?

    // Cold-start restore uses the newest progress row from non-deleted books as the last compact-player item.
    @Query("""
        SELECT book_progress.* FROM book_progress
        INNER JOIN books ON books.id = book_progress.bookId
        WHERE books.status != 'DELETED'
        ORDER BY book_progress.lastPlayedAt DESC
        LIMIT 1
    """)
    suspend fun getLastPlayedProgressSync(): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: BookProgressEntity)

    @Query("UPDATE books SET title = :title, author = :author, narrator = :narrator, description = :description, totalDurationMs = :duration WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, author: String, narrator: String, description: String, duration: Long)

    // 详尽的中文注释：专门用于当缓存被清理丢失后，后台重新提取并局部更新书籍封面的物理缓存路径、背景主色调与最新扫描时间戳。
    // 使用局部 UPDATE 避免覆盖其他并发更新的字段，防止引发多线程写入竞态。此外，通过更新 lastScannedAt 强制让 Flow 重发以触发布局重绘自动刷新。
    @Query("UPDATE books SET coverPath = :coverPath, thumbnailPath = :thumbnailPath, backgroundColorArgb = :backgroundColorArgb, lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateCoverPaths(id: String, coverPath: String?, thumbnailPath: String?, backgroundColorArgb: Int?, lastScannedAt: Long)
}