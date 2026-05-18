package com.viel.aplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<ScanSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ScanSessionEntity)

    // Coordinator writes RUNNING first, then completes or abandons explicitly.
    @Query("UPDATE scan_sessions SET status = 'COMPLETED', completedAt = :completedAt, discoveredBookCount = :discoveredBookCount, unavailableBookCount = :unavailableBookCount, partialBookCount = :partialBookCount, updatedBookCount = :updatedBookCount, pendingActionCount = :pendingActionCount, summaryJson = :summaryJson WHERE id = :id")
    suspend fun markCompleted(
        id: String,
        completedAt: Long = System.currentTimeMillis(),
        discoveredBookCount: Int = 0,
        unavailableBookCount: Int = 0,
        partialBookCount: Int = 0,
        updatedBookCount: Int = 0,
        pendingActionCount: Int = 0,
        summaryJson: String = ""
    )

    // Failed scans are abandoned; RUNNING results are not applied as valid output.
    @Query("UPDATE scan_sessions SET abandonedAt = :abandonedAt WHERE id = :id")
    suspend fun markAbandoned(id: String, abandonedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getSessionById(id: String): ScanSessionEntity?

    @Query("SELECT * FROM pending_scan_actions WHERE scanSessionId = :sessionId")
    fun getActionsForSession(sessionId: String): Flow<List<PendingScanActionEntity>>

    @Query("SELECT * FROM pending_scan_actions WHERE actionKey = :actionKey")
    suspend fun getActionByKey(actionKey: String): PendingScanActionEntity?

    // A new scan owns the current pending queue, so stale pending actions are cleared before scanning.
    @Query("DELETE FROM pending_scan_actions")
    suspend fun clearPendingActions()

    // Same actionKey refreshes the current pending item instead of adding duplicates.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAction(action: PendingScanActionEntity)

    @Query("SELECT * FROM scan_sessions WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT 1")
    fun observeLatestCompletedSession(): kotlinx.coroutines.flow.Flow<ScanSessionEntity?>
}
