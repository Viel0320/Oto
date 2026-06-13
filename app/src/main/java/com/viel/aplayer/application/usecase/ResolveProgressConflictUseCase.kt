package com.viel.aplayer.application.usecase

import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.data.db.AudiobookSchema

// Title: ResolveProgressConflictUseCase Definition (Arbitrates local vs server progress conflicts for ABS playback)
// This usecase decouples PlaybackViewModel from Infrastructure's AbsProgressConflictCoordinator and specific Room entity shapes.
class ResolveProgressConflictUseCase(
    private val coordinator: AbsProgressConflictCoordinator
) {
    // Title: PlaybackDecisionResult (Decoupled application layer decision outcome)
    sealed interface PlaybackDecisionResult {
        data object ContinueLocal : PlaybackDecisionResult
        data class ApplyRemote(val conflict: ConflictSnapshot) : PlaybackDecisionResult
        data class AskUser(val conflict: ConflictSnapshot) : PlaybackDecisionResult
    }

    // Title: ConflictSnapshot (Decoupled snapshot carrying UI presentation properties)
    // Hides Room entities and ABS-specific classes from UI view models.
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

    // Title: Prepare Playback (Delegates to conflict coordinator and maps to decoupled result)
    suspend fun preparePlayback(bookId: String): PlaybackDecisionResult {
        return when (val decision = coordinator.preparePlayback(bookId)) {
            AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal -> PlaybackDecisionResult.ContinueLocal
            is AbsProgressConflictCoordinator.PlaybackDecision.ApplyRemote -> PlaybackDecisionResult.ApplyRemote(decision.conflict.toSnapshot())
            is AbsProgressConflictCoordinator.PlaybackDecision.AskUser -> PlaybackDecisionResult.AskUser(decision.conflict.toSnapshot())
        }
    }

    // Title: Accept Local Progress (Forwards local authorization decision to conflict coordinator)
    fun acceptLocalProgress(bookId: String) {
        coordinator.acceptLocalProgress(bookId)
    }

    // Title: Accept Remote Progress (Forwards remote authorization choice to conflict coordinator)
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
