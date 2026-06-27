package com.viel.oto.abs.mapping

import com.viel.oto.abs.net.dto.AbsUserProgressDto
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookProgressEntity

class AbsProgressConflictResolver {
    /**
     * Classifies local and remote progress into deterministic sync actions.
     * Keeps comparison rules outside transport/session classes so upload and playback prompts share the same behavior.
     */
    enum class Decision {
        InSync,
        Conflict
    }

    /**
     * Filters out harmless clock and polling drift.
     * ABS reports seconds while Oto stores milliseconds, so playback checkpoints within thirty seconds are treated as equivalent drift.
     */
    companion object {
        const val POSITION_CONFLICT_THRESHOLD_MS: Long = 30_000L
    }

    /**
     * Compares positions after converting ABS seconds into local milliseconds.
     * Missing local rows and missing remote position fields are normalized to an unfinished 0ms checkpoint
     * so "not started" participates in the same arbitration rules as any other position.
     */
    fun resolve(
        local: BookProgressEntity?,
        remote: AbsUserProgressDto?,
        localReadStatus: AudiobookSchema.ReadStatus? = null
    ): Decision {
        val localPositionMs = local.normalizedPositionMs()
        val remotePositionMs = remote.normalizedPositionMs()
        if (hasFinishedConflict(local, localReadStatus, remote?.isFinished)) return Decision.Conflict
        return resolvePositions(localPositionMs, remotePositionMs)
    }

    /**
     * Compares already-mapped local progress entities.
     * Playback prompts use this overload after the remote DTO has been converted into the same unit model as Room progress,
     * while still treating a missing candidate as an explicit 0ms "not started" checkpoint.
     */
    fun resolveLocalCandidates(
        local: BookProgressEntity?,
        remote: BookProgressEntity?,
        localReadStatus: AudiobookSchema.ReadStatus? = null,
        remoteIsFinished: Boolean? = null
    ): Decision {
        val localPositionMs = local.normalizedPositionMs()
        val remotePositionMs = remote.normalizedPositionMs()
        if (hasFinishedConflict(local, localReadStatus, remoteIsFinished)) return Decision.Conflict
        return resolvePositions(localPositionMs, remotePositionMs)
    }

    /**
     * Compares finished state only when both sides expose a concrete completion flag.
     * A missing local progress row is deliberately treated as unfinished even if stale book metadata says FINISHED.
     */
    private fun hasFinishedConflict(
        local: BookProgressEntity?,
        localReadStatus: AudiobookSchema.ReadStatus?,
        remoteIsFinished: Boolean?
    ): Boolean {
        if (remoteIsFinished == null || localReadStatus == null) return false
        val localIsFinished = local != null && localReadStatus == AudiobookSchema.ReadStatus.FINISHED
        return localIsFinished != remoteIsFinished
    }

    private fun resolvePositions(localPositionMs: Long, remotePositionMs: Long): Decision {
        val deltaMs = kotlin.math.abs(localPositionMs - remotePositionMs)
        return if (deltaMs <= POSITION_CONFLICT_THRESHOLD_MS) {
            Decision.InSync
        } else {
            Decision.Conflict
        }
    }

    fun shouldApplyRemoteProgress(
        local: BookProgressEntity?,
        remote: AbsUserProgressDto?,
        isCurrentlyPlaying: Boolean,
        localReadStatus: AudiobookSchema.ReadStatus? = null
    ): Boolean {
        if (remote == null) return false
        if (isCurrentlyPlaying) return false
        return when (resolve(local, remote, localReadStatus)) {
            Decision.InSync -> {
                val remoteUpdatedAt = remote.lastUpdate ?: return false
                val localUpdatedAt = local?.lastPlayedAt ?: return true
                remoteUpdatedAt > localUpdatedAt
            }
            Decision.Conflict -> isRemoteNewerThanLocal(local, remote)
        }
    }

    /**
     * Lets newer device checkpoints overwrite stale server progress.
     * A large position delta alone is not a conflict when the local save happened after the last known ABS checkpoint.
     */
    fun shouldUploadLocalProgress(
        local: BookProgressEntity,
        remote: AbsUserProgressDto?,
        localReadStatus: AudiobookSchema.ReadStatus? = null
    ): Boolean =
        when (resolve(local, remote, localReadStatus)) {
            Decision.Conflict -> isLocalNewerThanRemote(local, remote)
            Decision.InSync -> true
        }

    /**
     * Supports both absolute seconds and ratio-duration ABS payloads.
     * Missing remote position data is an explicit not-started checkpoint rather than an absent candidate.
     */
    private fun AbsUserProgressDto?.normalizedPositionMs(): Long {
        val remote = this ?: return 0L
        val currentTimeSec = remote.currentTime ?: remote.progress?.let { ratio ->
            remote.duration?.let { totalDurationSec -> ratio * totalDurationSec }
        } ?: return 0L
        return (currentTimeSec * 1000.0).toLong().coerceAtLeast(0L)
    }

    /**
     * Treats a missing local progress row as a stable not-started checkpoint for conflict comparison.
     */
    private fun BookProgressEntity?.normalizedPositionMs(): Long =
        this?.globalPositionMs?.coerceAtLeast(0L) ?: 0L

    private fun isRemoteNewerThanLocal(local: BookProgressEntity?, remote: AbsUserProgressDto?): Boolean {
        val remoteUpdatedAt = remote?.lastUpdate ?: return false
        val localUpdatedAt = local?.lastPlayedAt ?: return true
        return remoteUpdatedAt > localUpdatedAt
    }

    private fun isLocalNewerThanRemote(local: BookProgressEntity, remote: AbsUserProgressDto?): Boolean {
        val remoteUpdatedAt = remote?.lastUpdate ?: return true
        return local.lastPlayedAt > remoteUpdatedAt
    }
}
