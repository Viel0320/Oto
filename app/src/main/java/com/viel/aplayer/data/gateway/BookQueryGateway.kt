package com.viel.aplayer.data.gateway

import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import kotlinx.coroutines.flow.Flow

/**
 * Decoupled Domain Gateway Interface (BookQueryGateway)
 * Focuses on audiobook info retrieval, metadata, bookmarks, and chapter database queries and maintenance.
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Dependencies: Expose narrow, boundary-clear read/write logic for upstream ViewModels and background services, excluding playback tracking and directory scanning.
 * 2. Promote Dependency Inversion: Provide front-end abstractions to phase out the bloated LibraryRepository.
 */
interface BookQueryGateway {

    /**
     * Reactive Audiobooks Stream (Observe entire library)
     * Reactively observes all audiobooks stored in the local database.
     */
    val audiobooks: Flow<List<BookWithProgress>>

    /**
     * Query Book Entity by ID (Synchronous fetch)
     * Fetches the audiobook entity matching the unique primary key ID.
     */
    suspend fun getBookById(id: String): BookEntity?

    /**
     * Observe Book State (Reactive ID query)
     * Reactively tracks state changes of the audiobook specified by ID.
     */
    fun observeBookById(id: String): Flow<BookEntity?>

    /**
     * Search Audiobooks (Fuzzy keyword search)
     * Performs a fuzzy text search on title, author, and narrator fields.
     */
    fun searchAudiobooks(query: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Year (Categorization helper)
     * Filters audiobooks by their publication/creation year.
     */
    fun filterByYear(year: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Author (Author exact match)
     * Filters audiobooks based on the exact author name.
     */
    fun filterByAuthor(author: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Author with Limit (Personalized recommendation)
     * Filters audiobooks by author with size limits, excluding the book currently in playback.
     */
    fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * Filter by Narrator (Narrator exact match)
     * Filters audiobooks based on the exact narrator name.
     */
    fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Narrator with Limit (Personalized recommendation)
     * Filters audiobooks by narrator with size limits, excluding the book currently in playback.
     */
    fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * Get Recently Added (Ingestion history query)
     * Retrieves a list of recently imported audiobooks, constrained by a maximum limit.
     */
    fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>>

    /**
     * Get Personalized Fallback Recommendations (Recommendation padding)
     * Retrieves recently added audiobooks excluding the active book, ensuring no author/narrator overlaps.
     */
    fun getRecentlyAddedExclusive(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>>

    /**
     * Logical Audiobook Deletion (Soft delete command)
     * Soft deletes the audiobook record from the database, retaining identifiers for future scan comparisons.
     */
    suspend fun deleteBook(bookId: String)

    /**
     * Update Reading Status (User state modification)
     * Manually updates the reading progress category (e.g., STARTED, FINISHED) for the audiobook.
     */
    suspend fun updateBookReadStatus(bookId: String, readStatus: String)

    /**
     * Update Text Metadata (Manual editor override)
     * Overwrites text attributes of the audiobook (title, author, narrator, etc.) in the database.
     */
    // Metadata Detail Updater Contract (Declares details update interface including series name)
    // Updates book details with series support.
    suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        series: String
    )

    /**
     * Get Associated Audio Tracks (Synchronous tracks fetch)
     * Synchronously fetches the list of audio files mapped to the specified audiobook.
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity>

    /**
     * Get All Associated Files (Synchronous physical asset inventory)
     * Synchronously fetches all database file records (audio tracks and manifest sidecars) for the book.
     */
    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity>

    /**
     * Update Tag Metadata (Silent parser synchronization)
     * Allows background sync pipelines to silently update audio tag fields (duration, details) in the database.
     */
    fun updateMetadata(
        bookId: String,
        title: String?,
        author: String?,
        narrator: String?,
        description: String?,
        duration: Long
    )

    /**
     * Observe Audiobook Chapters (Reactive index flow)
     * Reactively observes the list of chapters associated with the specified audiobook.
     */
    fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>>

    /**
     * Get Audiobook Chapters (Synchronous chapter query)
     * Synchronously queries all chapter entities resolved for the specified audiobook.
     */
    suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile>

    /**
     * Bulk Save Chapters (Write transaction entry)
     * Replaces or batch inserts newly parsed chapters for the specified audiobook.
     */
    fun saveChapters(bookId: String, chapters: List<ChapterEntity>)

    /**
     * Observe User Bookmarks (Reactive bookmark flow)
     * Reactively monitors bookmark entries created by the user for the target audiobook.
     */
    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    /**
     * Add Bookmark Entry (User marking action)
     * Appends a new bookmark at the specified global position offset.
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String)

    /**
     * Update Bookmark Details (User edit action)
     * Overwrites details (such as notes/titles) of a specific bookmark record.
     */
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    /**
     * Delete Bookmark Record (User removal action)
     * Permanently deletes a specific bookmark record from the database.
     */
    suspend fun deleteBookmark(bookmark: BookmarkEntity)
}
