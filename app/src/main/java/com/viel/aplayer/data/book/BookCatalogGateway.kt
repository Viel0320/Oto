package com.viel.aplayer.data.book

import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import kotlinx.coroutines.flow.Flow

/**
 * Book Catalog Gateway (Application-facing audiobook read and inventory seam)
 *
 * Owns catalog streams, search filters, selected-book reads, and stored file inventories without exposing
 * bookmark, chapter mutation, metadata editing, or deletion commands to read-only callers.
 */
interface BookCatalogGateway {
    /**
     * Reactive Audiobooks Stream (Observe entire library)
     *
     * Reactively observes all audiobooks stored in the local database with playback progress projections.
     */
    val audiobooks: Flow<List<BookWithProgress>>

    /**
     * Query Book Entity by ID (Synchronous fetch)
     *
     * Fetches the audiobook entity matching the unique primary key ID.
     */
    suspend fun getBookById(id: String): BookEntity?

    /**
     * Observe Book State (Reactive ID query)
     *
     * Reactively tracks state changes of the audiobook specified by ID.
     */
    fun observeBookById(id: String): Flow<BookEntity?>

    /**
     * Search Audiobooks (Fuzzy keyword search)
     *
     * Performs a fuzzy text search on title, author, and narrator fields.
     */
    fun searchAudiobooks(query: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Year (Categorization helper)
     *
     * Filters audiobooks by their publication or creation year.
     */
    fun filterByYear(year: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Author (Author exact match)
     *
     * Filters audiobooks based on the exact author name.
     */
    fun filterByAuthor(author: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Author with Limit (Personalized recommendation)
     *
     * Filters audiobooks by author with size limits, excluding the book currently in playback.
     */
    fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * Filter by Narrator (Narrator exact match)
     *
     * Filters audiobooks based on the exact narrator name.
     */
    fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>>

    /**
     * Filter by Narrator with Limit (Personalized recommendation)
     *
     * Filters audiobooks by narrator with size limits, excluding the book currently in playback.
     */
    fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * Get Recently Added (Ingestion history query)
     *
     * Retrieves a list of recently imported audiobooks, constrained by a maximum limit.
     */
    fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>>

    /**
     * Get Personalized Fallback Recommendations (Recommendation padding)
     *
     * Retrieves recently added audiobooks excluding the active book, ensuring no author or narrator overlaps.
     */
    fun getRecentlyAddedExclusive(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>>

    /**
     * Get Associated Audio Tracks (Synchronous tracks fetch)
     *
     * Synchronously fetches the list of audio files mapped to the specified audiobook.
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity>

    /**
     * Get All Associated Files (Synchronous physical asset inventory)
     *
     * Synchronously fetches all database file records, including audio tracks and manifest sidecars for the book.
     */
    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity>
}
