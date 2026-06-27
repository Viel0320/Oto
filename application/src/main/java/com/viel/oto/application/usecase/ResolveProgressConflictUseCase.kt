package com.viel.oto.application.usecase

import com.viel.oto.abs.playback.AbsProgressConflictCoordinator
import com.viel.oto.data.db.AudiobookSchema

class ResolveProgressConflictUseCase(
    private val coordinator: AbsProgressConflictCoordinator
) {
    sealed interface PlaybackDecisionResult {
        data object ContinueLocal : PlaybackDecisionResult
        data class ApplyRemote(val conflict: ConflictSnapshot) : PlaybackDecisionResult
        data class AskUser(val conflict: ConflictSnapshot) : PlaybackDecisionResult
    }

    /**
     * UI-facing conflict projection using local progress units.
     * A missing local progress row is normalized to an unfinished 0ms checkpoint, while remote completion remains nullable to preserve ABS "unknown" semantics.
     */
    data class ConflictSnapshot(
        val bookId: String,
        val bookTitle: String,
        val localPositionMs: Long,
        val remotePositionMs: Long,
        val localUpdatedAt: Long?,
        val remoteUpdatedAt: Long?,
        val localFinished: Boolean,
        val remoteFinished: Boolean?,
        internal val rawConflict: AbsProgressConflictCoordinator.ProgressConflict
    )

    suspend fun preparePlayback(bookId: String): PlaybackDecisionResult {
        return when (val decision = coordinator.preparePlayback(bookId)) {
            AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal -> PlaybackDecisionResult.ContinueLocal
            is AbsProgressConflictCoordinator.PlaybackDecision.ApplyRemote -> PlaybackDecisionResult.ApplyRemote(decision.conflict.toSnapshot())
            is AbsProgressConflictCoordinator.PlaybackDecision.AskUser -> PlaybackDecisionResult.AskUser(decision.conflict.toSnapshot())
        }
    }

    fun acceptLocalProgress(bookId: String) {
        coordinator.acceptLocalProgress(bookId)
    }

    suspend fun acceptRemoteProgress(conflict: ConflictSnapshot) {
        coordinator.acceptRemoteProgress(conflict.rawConflict)
    }

    private fun AbsProgressConflictCoordinator.ProgressConflict.toSnapshot() = ConflictSnapshot(
        bookId = book.id,
        bookTitle = book.title,
        localPositionMs = localProgress?.globalPositionMs?.coerceAtLeast(0L) ?: 0L,
        remotePositionMs = remoteProgress.globalPositionMs.coerceAtLeast(0L),
        localUpdatedAt = localProgress?.lastPlayedAt,
        remoteUpdatedAt = remoteProgress.lastPlayedAt,
        localFinished = localProgress != null && book.readStatus == AudiobookSchema.ReadStatus.FINISHED,
        remoteFinished = remoteIsFinished,
        rawConflict = this
    )
}
