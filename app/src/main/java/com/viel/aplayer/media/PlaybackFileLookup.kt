package com.viel.aplayer.media

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity

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

interface PlaybackRootLookup {
    // Root Lookup Boundary (Resolve storage roots without exposing DAOs to Media3 data sources)
    // Manual Cache Source Classification (Identify local SAF roots without exposing mutable library-root operations)
    // Playback needs source type only to decide whether a manual-cache lookup is meaningful before falling back to upstream reads.
    suspend fun getRootById(rootId: String): LibraryRootEntity?
}

class DefaultPlaybackRootLookup(
    private val libraryRootDao: LibraryRootDao
) : PlaybackRootLookup {
    // Room Root Lookup Adapter (Delegates source-type reads to the existing library-root DAO)
    // Keeping this adapter beside PlaybackFileLookup gives data sources a narrow read-only playback dependency view.
    override suspend fun getRootById(rootId: String): LibraryRootEntity? =
        libraryRootDao.getRootById(rootId)
}
