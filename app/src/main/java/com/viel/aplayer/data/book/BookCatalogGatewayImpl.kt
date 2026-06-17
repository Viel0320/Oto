package com.viel.aplayer.data.book

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.BookMinWithProgress
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Book Catalog Service (Implements BookCatalogGateway)
 *
 * Owns read/search/file-inventory projections only. Every list flow runs through [checkCovers] so cover
 * self-healing stays attached to catalog reads without leaking into metadata, bookmark, or chapter seams.
 */
@OptIn(UnstableApi::class)
class BookCatalogGatewayImpl(
    private val bookDao: BookDao,
    private val coverRecoveryHelper: CoverRecoveryHelper
) : BookCatalogGateway {

    /**
     * Reactive Cover Self-Healing Interceptor (Flow thread redirection guard)
     * Maps each row to a full BookWithProgress and triggers cover regeneration on Dispatchers.IO to keep
     * filesystem probes off the UI collector thread.
     */
    private fun Flow<List<BookMinWithProgress>>.checkCovers(): Flow<List<BookWithProgress>> = this.map { list ->
        list.map { it.toBookWithProgress() }.onEach { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it.book) }
    }.flowOn(Dispatchers.IO)

    override val audiobooks: Flow<List<BookWithProgress>>
        get() = bookDao.getAllBooksWithProgress().checkCovers()

    override suspend fun getBookById(id: String): BookEntity? = withContext(Dispatchers.IO) {
        bookDao.getBookById(id)?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
    }

    override fun observeBookById(id: String): Flow<BookEntity?> {
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

    override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getAllFilesForBookList(bookId)
    }
}
