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

    data class ConflictSnapshot(
        val bookId: String,
        val bookTitle: String,
        val localPositionMs: Long?,
        val remotePositionMs: Long,
        val localUpdatedAt: Long?,
        val remoteUpdatedAt: Long?,
        val localFinished: Boolean,
        val remoteFinished: Boolean,
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
        localPositionMs = localProgress?.globalPositionMs,
        remotePositionMs = remoteProgress.globalPositionMs,
        localUpdatedAt = localProgress?.lastPlayedAt,
        remoteUpdatedAt = remoteProgress.lastPlayedAt,
        localFinished = book.readStatus == AudiobookSchema.ReadStatus.FINISHED,
        remoteFinished = remoteIsFinished == true,
        rawConflict = this
    )
}
