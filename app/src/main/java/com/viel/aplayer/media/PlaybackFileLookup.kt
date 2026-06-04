package com.viel.aplayer.media

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookFileEntity

/**
 * Playback File Retrieval Boundary (Interface for looking up playable audio files)
 *
 * Design objectives:
 * 1. Eliminate heavy, direct database dependency within the playback engine layer.
 * 2. Enable dependency inversion, letting testing components mock storage lookup calls.
 */
interface PlaybackFileLookup {
    /**
     * Get Track File Record (Query audio file entity asynchronously by its unique ID)
     */
    suspend fun getBookFileById(bookFileId: String): BookFileEntity?
}

/**
 * Room-backed Playback File Resolver (Standard implementation relying on BookDao)
 */
class DefaultPlaybackFileLookup(
    private val bookDao: BookDao
) : PlaybackFileLookup {
    
    override suspend fun getBookFileById(bookFileId: String): BookFileEntity? {
        // Direct Query Delegate (Route requests down to low-level BookDao layer)
        return bookDao.getBookFileById(bookFileId)
    }
}
