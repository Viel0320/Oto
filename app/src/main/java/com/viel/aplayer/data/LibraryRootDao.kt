package com.viel.aplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryRootDao {
    @Query("SELECT * FROM library_roots")
    fun getAllRoots(): Flow<List<LibraryRootEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoot(root: LibraryRootEntity)

    @Delete
    suspend fun deleteRoot(root: LibraryRootEntity)
}
