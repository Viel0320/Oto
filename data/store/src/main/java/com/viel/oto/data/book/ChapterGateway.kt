package com.viel.oto.data.book

import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.ChapterWithBookFile
import kotlinx.coroutines.flow.Flow

/**
 * Application-facing audiobook chapter seam.
 *
 * Groups reactive chapter timelines, synchronous playback timeline reads, and chapter replacement writes
 * without coupling chapter consumers to catalog filtering or bookmark commands.
 */
interface ChapterGateway {
    /**
     * Reactive index flow.
     *
     * Reactively observes the list of chapters associated with the specified audiobook.
     */
    fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>>

    /**
     * Synchronous chapter query.
     *
     * Synchronously queries all chapter entities resolved for the specified audiobook.
     */
    suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile>

    /**
     * Write transaction entry.
     *
     * Replaces or batch inserts newly parsed chapters for the specified audiobook.
     */
    fun saveChapters(bookId: String, chapters: List<ChapterEntity>)
}
