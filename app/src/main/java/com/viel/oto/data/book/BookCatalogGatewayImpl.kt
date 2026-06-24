package com.viel.oto.data.book

import com.viel.oto.data.cover.CoverRecoveryGateway
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.BookMinWithProgress
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookWithProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implements BookCatalogGateway.
 *
 * Owns read/search/file-inventory projections only. Search and filter flows still run through [checkCovers] so
 * cover self-healing stays attached to those on-demand reads, while the first-frame [audiobooks] stream is kept
 * probe-free and relies on CoverRecoveryGateway's deferred background sweep instead. Self-heal triggers go through
 * the data-layer CoverRecoveryGateway rather than the media-layer helper directly.
 */
class BookCatalogGatewayImpl(
    private val bookDao: BookDao,
    private val coverRecoveryGateway: CoverRecoveryGateway
) : BookCatalogGateway {

    /**
     * Flow thread redirection guard.
     * Maps each row to a full BookWithProgress and triggers cover regeneration on Dispatchers.IO to keep
     * filesystem probes off the UI collector thread.
     */
    private fun Flow<List<BookMinWithProgress>>.checkCovers(): Flow<List<BookWithProgress>> = this.map { list ->
        list.map { it.toBookWithProgress() }.onEach { coverRecoveryGateway.triggerRecovery(it.book) }
    }.flowOn(Dispatchers.IO)

    private fun Flow<List<HomeCatalogRow>>.checkHomeCovers(): Flow<List<HomeCatalogRow>> = this.map { list ->
        list.onEach { row ->
            val tempBook = BookEntity(
                id = row.id,
                rootId = row.rootId,
                sourceType = row.sourceType,
                title = row.title,
                author = row.author,
                narrator = row.narrator,
                coverPath = row.coverPath,
                thumbnailPath = row.thumbnailPath,
                lastScannedAt = row.lastScannedAt,
                addedAt = row.addedAt,
                status = row.status,
                readStatus = row.readStatus,
                series = row.series
            )
            coverRecoveryGateway.triggerRecovery(tempBook)
        }
    }.flowOn(Dispatchers.IO)

    override val audiobooks: Flow<List<BookWithProgress>>
        get() = bookDao.getAllBooksWithProgress().map { list ->
            list.map { it.toBookWithProgress() }
        }.flowOn(Dispatchers.IO)

    override val homeCatalogRows: Flow<List<HomeCatalogRow>>
        get() = bookDao.observeHomeCatalogRows().checkHomeCovers()

    override suspend fun getBookById(id: String): BookEntity? = withContext(Dispatchers.IO) {
        bookDao.getBookById(id)?.also { coverRecoveryGateway.triggerRecovery(it) }
    }

    override fun observeBookById(id: String): Flow<BookEntity?> {
        return bookDao.observeBookById(id).map { book ->
            book?.also { coverRecoveryGateway.triggerRecovery(it) }
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

    override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getAllFilesForBookList(bookId)
    }
}
