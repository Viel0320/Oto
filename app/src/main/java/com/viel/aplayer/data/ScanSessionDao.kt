package com.viel.aplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<ScanSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getSessionById(id: String): ScanSessionEntity?

    @Query("SELECT * FROM pending_scan_actions WHERE scanSessionId = :sessionId")
    fun getActionsForSession(sessionId: String): Flow<List<PendingScanActionEntity>>

    @Query("SELECT * FROM pending_scan_actions WHERE actionKey = :actionKey")
    suspend fun getActionByKey(actionKey: String): PendingScanActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActions(actions: List<PendingScanActionEntity>)

    @Query("SELECT * FROM scan_sessions WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT 1")
    fun observeLatestCompletedSession(): kotlinx.coroutines.flow.Flow<ScanSessionEntity?>

    @Query("UPDATE pending_scan_actions SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateActionStatus(id: String, status: String, resolvedAt: Long = System.currentTimeMillis())
}
