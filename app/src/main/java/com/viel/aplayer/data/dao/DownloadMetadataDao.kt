package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadMetadataDao {
    // Download Metadata Upsert (Persists the latest book-level aggregate produced from Media3 download state)
    // The aggregate is replaceable because Media3 remains the authoritative file-level source for later reconciliation.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(metadata: DownloadMetadataEntity)

    // Download Metadata Snapshot (Reads one book aggregate for commands and recovery routines)
    // Missing rows represent BookCacheStatus.None at the application read-model boundary.
    @Query("SELECT * FROM download_metadata WHERE bookId = :bookId")
    suspend fun getMetadata(bookId: String): DownloadMetadataEntity?

    // Download Metadata Stream (Lets future UI layers observe cache status without reading Media3 directly)
    // The Flow emits null when metadata is deleted after cancel or removeDownload reconciliation.
    @Query("SELECT * FROM download_metadata WHERE bookId = :bookId")
    fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?>

    // Download Metadata List Stream (Provides the manual-download management read model with durable book aggregates)
    // Only manual downloads create rows, so this stream remains scoped to user-requested offline cache tasks.
    @Query("SELECT * FROM download_metadata ORDER BY updatedAt DESC")
    fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>>

    // Download Metadata Snapshot List (Provides one-shot maintenance input for cache cleanup jobs)
    // Background orphan cleanup needs a stable snapshot and should not collect the management-screen Flow.
    @Query("SELECT * FROM download_metadata ORDER BY updatedAt DESC")
    suspend fun getAllMetadata(): List<DownloadMetadataEntity>

    // Recoverable Download Query (Finds only non-terminal or retryable aggregates that require Media3 DownloadIndex reconciliation)
    // COMPLETED rows do not start the download runtime during app startup because their terminal state is already durable.
    @Query("""
        SELECT *
        FROM download_metadata
        WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'FAILED')
        ORDER BY updatedAt DESC
    """)
    suspend fun getRecoverableTasks(): List<DownloadMetadataEntity>

    // Recoverable Download Gate (Checks whether startup may resolve the lazy DownloadGraph runtime)
    // A count query avoids loading rows when the application only needs the smart-start decision.
    @Query("""
        SELECT COUNT(*) > 0
        FROM download_metadata
        WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'FAILED')
    """)
    suspend fun hasRecoverableTasks(): Boolean

    // Completed Download Count (Summarizes durable L1 manual-cache rows for cache statistics)
    // This count avoids constructing DownloadManager when settings or management screens only need lightweight persisted totals.
    @Query("SELECT COUNT(*) FROM download_metadata WHERE status = 'COMPLETED'")
    suspend fun getCompletedTaskCount(): Int

    // Download Metadata Delete By Book (Clears the app-level aggregate after Media3 removes every file-level download)
    // Deleting the row is the only supported persistence path for the user-visible NONE state.
    @Query("DELETE FROM download_metadata WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    // Download Metadata Delete Entity (Supports tests and cleanup flows that already hold the aggregate row)
    // This delegates to Room's entity delete path without changing the deletion semantics.
    @Delete
    suspend fun delete(metadata: DownloadMetadataEntity)
}
