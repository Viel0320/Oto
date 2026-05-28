package com.viel.aplayer.data.service

import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.media.BookPlaybackPlan
import kotlinx.coroutines.flow.Flow

/**
 * 有声书查询与维护应用服务（实现了 BookQueryGateway 网关）。
 *
 * 核心设计目标：
 * 1. 增量重构过渡层：在迁移阶段，底层实际仍旧委托给已有的上帝仓库 [BookLibraryRepository]。
 * 2. 规避编译破坏：在上游调用方和底层数据结构调整期间，提供零耦合的代理服务层。
 * 3. 方便后续直连 DAO：在未来 M6 阶段，可以直接在该类中去掉对 [BookLibraryRepository] 的引用，改为直接注入 DAO 实体。
 */
class BookQueryService(
    private val bookLibraryRepository: BookLibraryRepository
) : BookQueryGateway {

    override val audiobooks: Flow<List<BookWithProgress>>
        get() = bookLibraryRepository.audiobooks

    override suspend fun getBookById(id: String): BookEntity? {
        return bookLibraryRepository.getBookById(id)
    }

    override fun observeBookById(id: String): Flow<BookEntity?> {
        return bookLibraryRepository.observeBookById(id)
    }

    override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.searchAudiobooks(query)
    }

    override fun filterByYear(year: String): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.filterByYear(year)
    }

    override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.filterByAuthor(author)
    }

    override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.filterByAuthorLimited(author, excludeId, limit)
    }

    override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.filterByNarrator(narrator)
    }

    override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.filterByNarratorLimited(narrator, excludeId, limit)
    }

    override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.getRecentlyAdded(limit)
    }

    override fun getRecentlyAddedExclusive(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>> {
        return bookLibraryRepository.getRecentlyAddedExclusive(currentId, authors, narrators, limit)
    }

    override suspend fun deleteBook(bookId: String) {
        bookLibraryRepository.deleteBook(bookId)
    }

    override suspend fun updateBookReadStatus(bookId: String, readStatus: String) {
        bookLibraryRepository.updateBookReadStatus(bookId, readStatus)
    }

    override suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String
    ) {
        bookLibraryRepository.updateBookDetails(id, title, author, narrator, description, year)
    }

    override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> {
        return bookLibraryRepository.getFilesForBookSync(bookId)
    }

    override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> {
        return bookLibraryRepository.getAllFilesForBookSync(bookId)
    }

    override fun observeLatestScanSession(): Flow<ScanSessionEntity?> {
        return bookLibraryRepository.observeLatestScanSession()
    }

    override suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? {
        return bookLibraryRepository.getPlaybackPlan(bookId)
    }

    override fun updateMetadata(
        bookId: String,
        title: String?,
        author: String?,
        narrator: String?,
        description: String?,
        duration: Long
    ) {
        bookLibraryRepository.updateMetadata(bookId, title, author, narrator, description, duration)
    }

    override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> {
        return bookLibraryRepository.getChapters(bookId)
    }

    override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> {
        return bookLibraryRepository.getChaptersForBookSync(bookId)
    }

    override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        bookLibraryRepository.saveChapters(bookId, chapters)
    }

    override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> {
        return bookLibraryRepository.getBookmarks(bookId)
    }

    override suspend fun addBookmark(bookId: String, position: Long, title: String) {
        bookLibraryRepository.addBookmark(bookId, position, title)
    }

    override suspend fun updateBookmark(bookmark: BookmarkEntity) {
        bookLibraryRepository.updateBookmark(bookmark)
    }

    override suspend fun deleteBookmark(bookmark: BookmarkEntity) {
        bookLibraryRepository.deleteBookmark(bookmark)
    }
}
