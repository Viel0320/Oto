package com.viel.aplayer.media

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity

/**
 * Interface for looking up playable audio files.
 *
 * Design objectives:
 * 1. Eliminate heavy, direct database dependency within the playback engine layer.
 * 2. Enable dependency inversion, letting testing components mock storage lookup calls.
 */
interface PlaybackFileLookup {
    /**
     * Query audio file entity asynchronously by its unique ID.
     */
    suspend fun getBookFileById(bookFileId: String): BookFileEntity?
}

/**
 * Standard implementation relying on BookDao.
 */
class DefaultPlaybackFileLookup(
    private val bookDao: BookDao
) : PlaybackFileLookup {

    override suspend fun getBookFileById(bookFileId: String): BookFileEntity? {
        return bookDao.getBookFileById(bookFileId)
    }
}

interface PlaybackRootLookup {
    suspend fun getRootById(rootId: String): LibraryRootEntity?
}

class DefaultPlaybackRootLookup(
    private val libraryRootDao: LibraryRootDao
) : PlaybackRootLookup {
    override suspend fun getRootById(rootId: String): LibraryRootEntity? =
        libraryRootDao.getRootById(rootId)
}
