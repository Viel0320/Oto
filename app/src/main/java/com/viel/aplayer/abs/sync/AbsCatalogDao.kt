package com.viel.aplayer.abs.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity

interface AbsCatalogStore {
    suspend fun getBookById(bookId: String): BookEntity?
    suspend fun getMirrorsByRootId(rootId: String): List<AbsItemMirrorEntity>
    suspend fun getSyncState(rootId: String): AbsSyncStateEntity?
    /**
     * Catalog Mirror Materialization Boundary (Persists only the ABS catalog shape)
     * Book progress is intentionally absent from this contract so remote progress must flow through AbsAuthorizedProgressSynchronizer.
     */
    suspend fun upsertCatalogMirror(
        book: BookEntity,
        files: List<BookFileEntity>,
        chapters: List<ChapterEntity>,
        mirror: AbsItemMirrorEntity,
        syncState: AbsSyncStateEntity
    )
    suspend fun replaceMirrors(mirrors: List<AbsItemMirrorEntity>)
    suspend fun saveSyncState(syncState: AbsSyncStateEntity)
    // Update Book Status Signature: Update status parameter type to BookStatus enum for type safety.
    suspend fun updateBookStatus(bookId: String, status: AudiobookSchema.BookStatus)
}

@Dao
abstract class AbsCatalogDao : AbsCatalogStore {
    @Query("SELECT * FROM books WHERE id = :bookId")
    abstract override suspend fun getBookById(bookId: String): BookEntity?

    @Query("SELECT * FROM abs_item_mirror WHERE rootId = :rootId")
    abstract override suspend fun getMirrorsByRootId(rootId: String): List<AbsItemMirrorEntity>

    @Query("SELECT * FROM abs_sync_state WHERE rootId = :rootId")
    abstract override suspend fun getSyncState(rootId: String): AbsSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertBookFiles(files: List<BookFileEntity>)

    @Query("DELETE FROM book_files WHERE bookId = :bookId AND id NOT IN (:keepIds)")
    protected abstract suspend fun deleteMissingFiles(bookId: String, keepIds: List<String>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    protected abstract suspend fun deleteChaptersForBook(bookId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertMirrorsInternal(mirrors: List<AbsItemMirrorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSyncStateInternal(syncState: AbsSyncStateEntity)

    @Query("UPDATE books SET status = :status WHERE id = :bookId")
    // Update Book Status Signature: Update status parameter type to BookStatus enum for type safety.
    abstract override suspend fun updateBookStatus(bookId: String, status: AudiobookSchema.BookStatus)

    @Transaction
    override suspend fun upsertCatalogMirror(
        book: BookEntity,
        files: List<BookFileEntity>,
        chapters: List<ChapterEntity>,
        mirror: AbsItemMirrorEntity,
        syncState: AbsSyncStateEntity
    ) {
        insertBook(book)
        insertBookFiles(files)
        if (files.isNotEmpty()) {
            deleteMissingFiles(book.id, files.map { file -> file.id })
        }
        deleteChaptersForBook(book.id)
        if (chapters.isNotEmpty()) {
            insertChapters(chapters)
        }
        // Catalog Materialization Scope (Persists catalog rows without applying remote listening state)
        // Remote progress and read status are intentionally handled by AbsAuthorizedProgressSynchronizer after catalog rows exist.
        insertMirrorsInternal(listOf(mirror))
        insertSyncStateInternal(syncState)
    }

    override suspend fun replaceMirrors(mirrors: List<AbsItemMirrorEntity>) {
        insertMirrorsInternal(mirrors)
    }

    override suspend fun saveSyncState(syncState: AbsSyncStateEntity) {
        insertSyncStateInternal(syncState)
    }
}
