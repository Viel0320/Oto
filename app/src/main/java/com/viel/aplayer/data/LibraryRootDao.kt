package com.viel.aplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryRootDao {
    @Query("SELECT * FROM library_roots")
    fun getAllRoots(): Flow<List<LibraryRootEntity>>

    // Rescan only traverses active roots.
    @Query("SELECT * FROM library_roots WHERE status = 'ACTIVE'")
    suspend fun getActiveRootsOnce(): List<LibraryRootEntity>

    @Query("SELECT * FROM library_roots WHERE id = :id")
    suspend fun getRootById(id: String): LibraryRootEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoot(root: LibraryRootEntity)

    // Rescan completion updates the root boundary timestamp.
    @Query("UPDATE library_roots SET lastScannedAt = :lastScannedAt, status = :status WHERE id = :id")
    suspend fun updateRootScanState(
        id: String,
        lastScannedAt: Long,
        status: String = AudiobookSchema.LibraryRootStatus.ACTIVE
    )

    @Delete
    suspend fun deleteRoot(root: LibraryRootEntity)
}
