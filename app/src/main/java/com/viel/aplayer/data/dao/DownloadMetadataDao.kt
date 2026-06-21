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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(metadata: DownloadMetadataEntity)

    @Query("SELECT * FROM download_metadata WHERE bookId = :bookId")
    suspend fun getMetadata(bookId: String): DownloadMetadataEntity?

    @Query("SELECT * FROM download_metadata WHERE bookId = :bookId")
    fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?>

    @Query("SELECT * FROM download_metadata ORDER BY updatedAt DESC")
    fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>>

    @Query("SELECT * FROM download_metadata ORDER BY updatedAt DESC")
    suspend fun getAllMetadata(): List<DownloadMetadataEntity>

    @Query("""
        SELECT *
        FROM download_metadata
        WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'FAILED')
        ORDER BY updatedAt DESC
    """)
    suspend fun getRecoverableTasks(): List<DownloadMetadataEntity>

    @Query("""
        SELECT COUNT(*) > 0
        FROM download_metadata
        WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'FAILED')
    """)
    suspend fun hasRecoverableTasks(): Boolean

    @Query("SELECT COUNT(*) FROM download_metadata WHERE status = 'COMPLETED'")
    suspend fun getCompletedTaskCount(): Int

    @Query("DELETE FROM download_metadata WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    @Delete
    suspend fun delete(metadata: DownloadMetadataEntity)
}
