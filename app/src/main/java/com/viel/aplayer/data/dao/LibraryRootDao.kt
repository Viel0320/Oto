package com.viel.aplayer.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity

@Dao
interface LibraryRootDao {
    @Query("SELECT * FROM library_roots")
    fun getAllRoots(): Flow<List<LibraryRootEntity>>

    // Rescan only traverses active roots.
    @Query("SELECT * FROM library_roots WHERE status = 'ACTIVE'")
    suspend fun getActiveRootsOnce(): List<LibraryRootEntity>

    @Query("SELECT * FROM library_roots WHERE id = :id")
    suspend fun getRootById(id: String): LibraryRootEntity?

    // Root insertion checks existing rows first so selecting the same SAF tree does not create duplicates.
    @Query("SELECT * FROM library_roots")
    suspend fun getAllRootsOnce(): List<LibraryRootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoot(root: LibraryRootEntity)

    @Query("UPDATE library_roots SET displayName = :displayName, grantedAt = :grantedAt, status = :status WHERE id = :id")
    suspend fun updateRootGrantState(
        id: String,
        displayName: String,
        grantedAt: Long,
        status: String = AudiobookSchema.LibraryRootStatus.ACTIVE
    )

    // Rescan completion updates the root boundary timestamp.
    @Query("UPDATE library_roots SET lastScannedAt = :lastScannedAt, status = :status WHERE id = :id")
    suspend fun updateRootScanState(
        id: String,
        lastScannedAt: Long,
        status: String = AudiobookSchema.LibraryRootStatus.ACTIVE
    )

    @Query("UPDATE library_roots SET status = :status WHERE id = :id")
    suspend fun updateRootStatus(id: String, status: String)

    @Delete
    suspend fun deleteRoot(root: LibraryRootEntity)
}