package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.viel.aplayer.data.entity.ScanSessionEntity

@Dao
interface ScanSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ScanSessionEntity)

    @Query("UPDATE scan_sessions SET status = 'COMPLETED', completedAt = :completedAt, discoveredBookCount = :discoveredBookCount, unavailableBookCount = :unavailableBookCount, partialBookCount = :partialBookCount, updatedBookCount = :updatedBookCount, summaryJson = :summaryJson WHERE id = :id")
    suspend fun markCompleted(
        id: String,
        completedAt: Long = System.currentTimeMillis(),
        discoveredBookCount: Int = 0,
        unavailableBookCount: Int = 0,
        partialBookCount: Int = 0,
        updatedBookCount: Int = 0,
        summaryJson: String = ""
    )

    @Query("UPDATE scan_sessions SET status = 'ABANDONED', abandonedAt = :abandonedAt WHERE id = :id")
    suspend fun markAbandoned(id: String, abandonedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getSessionById(id: String): ScanSessionEntity?
}
