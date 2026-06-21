package com.viel.aplayer.data.book

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Application-facing audiobook read and inventory seam.
 *
 * Owns catalog streams, search filters, selected-book reads, and stored file inventories without exposing
 * bookmark, chapter mutation, metadata editing, or deletion commands to read-only callers.
 */
interface BookCatalogGateway {
    /**
     * Observe entire library.
     *
     * Reactively observes all audiobooks stored in the local database with playback progress projections.
     */
    val audiobooks: Flow<List<BookWithProgress>>

    /**
     * Observe the shelf projection without Room relation rows.
     *
     * Real data services override this with a SQL join projection so Home can avoid the heavier
     * BookWithProgress relation graph on first paint. The default keeps existing lightweight test doubles source-compatible.
     */
    val homeCatalogRows: Flow<List<HomeCatalogRow>>
        get() = audiobooks.map { books -> books.map { book -> book.toHomeCatalogRow() } }

    /**
     * Synchronous fetch.
     *
     * Fetches the audiobook entity matching the unique primary key ID.
     */
    suspend fun getBookById(id: String): BookEntity?

    /**
     * Reactive ID query.
     *
     * Reactively tracks state changes of the audiobook specified by ID.
     */
    fun observeBookById(id: String): Flow<BookEntity?>

    /**
     * Fuzzy keyword search.
     *
     * Performs a fuzzy text search on title, author, and narrator fields.
     */
    fun searchAudiobooks(query: String): Flow<List<BookWithProgress>>

    /**
     * Categorization helper.
     *
     * Filters audiobooks by their publication or creation year.
     */
    fun filterByYear(year: String): Flow<List<BookWithProgress>>

    /**
     * Author exact match.
     *
     * Filters audiobooks based on the exact author name.
     */
    fun filterByAuthor(author: String): Flow<List<BookWithProgress>>

    /**
     * Personalized recommendation.
     *
     * Filters audiobooks by author with size limits, excluding the book currently in playback.
     */
    fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * Narrator exact match.
     *
     * Filters audiobooks based on the exact narrator name.
     */
    fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>>

    /**
     * Personalized recommendation.
     *
     * Filters audiobooks by narrator with size limits, excluding the book currently in playback.
     */
    fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * Ingestion history query.
     *
     * Retrieves a list of recently imported audiobooks, constrained by a maximum limit.
     */
    fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>>

    /**
     * Recommendation padding.
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
     * Synchronous tracks fetch.
     *
     * Synchronously fetches the list of audio files mapped to the specified audiobook.
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity>

    /**
     * Synchronous physical asset inventory.
     *
     * Synchronously fetches all database file records, including audio tracks and manifest sidecars for the book.
     */
    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity>
}

/**
 * Data-layer projection for the Home shelf.
 *
 * Carries only fields rendered or organized by Home, plus precomputed progress values from SQL, so callers do not need
 * Room relation wrappers or full persistence entities to build the first-screen catalog.
 */
data class HomeCatalogRow(
    val id: String,
    val rootId: String,
    val sourceType: AudiobookSchema.SourceType,
    val status: AudiobookSchema.BookStatus,
    val title: String,
    val author: String,
    val narrator: String,
    val year: String,
    val series: String,
    val totalDurationMs: Long,
    val totalFileSize: Long,
    val coverPath: String?,
    val thumbnailPath: String?,
    val lastScannedAt: Long,
    val addedAt: Long,
    val readStatus: AudiobookSchema.ReadStatus,
    val progressPercent: Int,
    val lastPlayedAt: Long
)

/**
 * Converts the legacy relation row for tests and transitional callers.
 *
 * Production Home reads override [BookCatalogGateway.homeCatalogRows] with a dedicated SQL projection; this fallback only
 * preserves the same semantics for existing in-memory gateway fakes.
 */
private fun BookWithProgress.toHomeCatalogRow(): HomeCatalogRow {
    return HomeCatalogRow(
        id = book.id,
        rootId = book.rootId,
        sourceType = book.sourceType,
        status = book.status,
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        year = book.year,
        series = book.series,
        totalDurationMs = book.totalDurationMs,
        totalFileSize = book.totalFileSize,
        coverPath = book.coverPath,
        thumbnailPath = book.thumbnailPath,
        lastScannedAt = book.lastScannedAt,
        addedAt = book.addedAt,
        readStatus = book.readStatus,
        progressPercent = progressPercent,
        lastPlayedAt = progress?.lastPlayedAt ?: 0L
    )
}
