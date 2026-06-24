package com.viel.oto.data.book

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.BookmarkDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookmarkEntity
import com.viel.oto.timeline.PositionMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Implements BookmarkGateway.
 *
 * Owns bookmark reads and writes. On insert it resolves global playback coordinates into a per-file
 * (index, offset, fingerprint) anchor via [PositionMapper] so bookmarks survive file re-ordering.
 */
class BookmarkGatewayImpl(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao
) : BookmarkGateway {

    private data class Anchor(
        val bookFileId: String?,
        val fileOffsetMs: Long,
        val fingerprint: String?,
        val anchorStatus: AudiobookSchema.AnchorStatus
    )

    override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> {
        return bookmarkDao.getBookmarksForBook(bookId)
    }

    override suspend fun addBookmark(bookId: String, position: Long, title: String) = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        val (bookFileId, fileOffsetMs, fingerprint, anchorStatus) = if (files.isNotEmpty()) {
            val (fileIndex, offset) = PositionMapper.globalToFilePosition(position, files)
            val file = files.getOrNull(fileIndex)
            Anchor(file?.id, offset, file?.fingerprint, AudiobookSchema.AnchorStatus.OK)
        } else {
            Anchor(null, 0L, null, AudiobookSchema.AnchorStatus.UNRESOLVED)
        }

        bookmarkDao.insert(BookmarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            globalPositionMs = position,
            bookFileId = bookFileId,
            fileOffsetMs = fileOffsetMs,
            fileFingerprint = fingerprint,
            anchorStatus = anchorStatus,
            title = title
        ))
        Unit
    }

    override suspend fun updateBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.insert(bookmark)
        Unit
    }

    override suspend fun deleteBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.delete(bookmark)
    }
}
