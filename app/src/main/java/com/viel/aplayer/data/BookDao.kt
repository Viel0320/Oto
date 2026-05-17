package com.viel.aplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun getAllBooksWithProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBookById(id: String): Flow<BookEntity?>

    @Query("SELECT sourceUri FROM books")
    suspend fun getAllSourceUris(): List<String>

    @Query("SELECT * FROM books")
    suspend fun getAllBooksOnce(): List<BookEntity>

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateBookStatus(id: String, status: String)

    @Query("SELECT uri FROM book_files")
    suspend fun getAllBookFileUris(): List<String>

    @Query("SELECT uri, bookId FROM book_files")
    suspend fun getFileUriToBookIdMap(): List<BookFileUriPair>

    data class BookFileUriPair(val uri: String, val bookId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("UPDATE books SET backgroundColorArgb = :color WHERE id = :id")
    suspend fun updateBackgroundColor(id: String, color: Int)

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE (title LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (title = '' OR title IS NULL)))
           OR (author LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (author = '' OR author IS NULL)))
           OR (narrator LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        ORDER BY title ASC
    """)
    fun searchBooksWithProgress(query: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE (year LIKE '%' || :year || '%' OR (:year = 'Unknown' AND (year = '' OR year IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByYearWithProgress(year: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByAuthorWithProgress(author: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByNarratorWithProgress(narrator: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        AND id != :excludeId ORDER BY title ASC LIMIT :limit
    """)
    fun filterByAuthorLimitedWithProgress(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        AND id != :excludeId ORDER BY title ASC LIMIT :limit
    """)
    fun filterByNarratorLimitedWithProgress(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC LIMIT :limit")
    fun getRecentlyAddedWithProgress(limit: Int): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE id != :currentId 
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

    // BookSource
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookSource(source: BookSourceEntity)

    // BookFile
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookFiles(files: List<BookFileEntity>)

    @Query("SELECT * FROM book_files WHERE bookId = :bookId ORDER BY `index` ASC")
    fun getFilesForBook(bookId: String): Flow<List<BookFileEntity>>

    @Query("SELECT * FROM book_files WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getFilesForBookList(bookId: String): List<BookFileEntity>

    // BookProgress
    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    fun getProgressForBook(bookId: String): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    suspend fun getProgressForBookSync(bookId: String): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: BookProgressEntity)

    // SubtitleTrack
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtitleTracks(tracks: List<SubtitleTrackEntity>)

    @Query("SELECT * FROM subtitle_tracks WHERE bookId = :bookId")
    fun getSubtitleTracksForBook(bookId: String): Flow<List<SubtitleTrackEntity>>

    @Query("SELECT * FROM subtitle_tracks WHERE bookId = :bookId")
    suspend fun getSubtitleTracksForBookList(bookId: String): List<SubtitleTrackEntity>

    @Query("UPDATE books SET title = :title, author = :author, narrator = :narrator, description = :description, totalDurationMs = :duration WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, author: String, narrator: String, description: String, duration: Long)
}
