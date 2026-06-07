package com.viel.aplayer.abs.mapping

import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookProgressEntity

class AbsProgressConflictResolver {
    /**
     * Progress Conflict Decision (Classifies local and remote progress into deterministic sync actions)
     * Keeps comparison rules outside transport/session classes so upload and playback prompts share the same behavior.
     */
    enum class Decision {
        RemoteMissing,
        LocalMissing,
        InSync,
        Conflict
    }

    /**
     * Progress Conflict Threshold (Filters out harmless clock and polling drift)
     * ABS reports seconds while APlayer stores milliseconds, so playback checkpoints within thirty seconds are treated as equivalent drift.
     */
    companion object {
        const val POSITION_CONFLICT_THRESHOLD_MS: Long = 30_000L
    }

    /**
     * Resolve Progress Decision (Compares positions after converting ABS seconds into local milliseconds)
     * Returns Conflict whenever both sides have meaningful progress and their positions diverge beyond the drift threshold.
     */
    fun resolve(
        local: BookProgressEntity?,
        remote: AbsUserProgressDto?,
        localReadStatus: String? = null
    ): Decision {
        val remotePositionMs = remote?.resolvedPositionMs() ?: return Decision.RemoteMissing
        if (local == null) return Decision.LocalMissing
        if (hasFinishedConflict(localReadStatus, remote.isFinished)) return Decision.Conflict
        return resolvePositions(local, remotePositionMs)
    }

    /**
     * Resolve Local Candidates (Compares already-mapped local progress entities)
     * Playback prompts use this overload after the remote DTO has been converted into the same unit model as Room progress.
     */
    fun resolveLocalCandidates(
        local: BookProgressEntity?,
        remote: BookProgressEntity?,
        localReadStatus: String? = null,
        remoteIsFinished: Boolean? = null
    ): Decision {
        if (remote == null) return Decision.RemoteMissing
        if (local == null) return Decision.LocalMissing
        if (hasFinishedConflict(localReadStatus, remoteIsFinished)) return Decision.Conflict
        return resolvePositions(local, remote.globalPositionMs)
    }

    /**
     * Finished State Conflict (Treats completed-vs-incomplete disagreement as a user-visible conflict)
     * A nearly identical timestamp can still represent different semantic progress when one side marks the book finished and the other does not.
     */
    private fun hasFinishedConflict(localReadStatus: String?, remoteIsFinished: Boolean?): Boolean {
        if (remoteIsFinished == null || localReadStatus == null) return false
        val localIsFinished = localReadStatus == AudiobookSchema.ReadStatus.FINISHED
        return localIsFinished != remoteIsFinished
    }

    private fun resolvePositions(local: BookProgressEntity, remotePositionMs: Long): Decision {
        val deltaMs = kotlin.math.abs(local.globalPositionMs - remotePositionMs)
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
        localReadStatus: String? = null
    ): Boolean {
        if (remote == null) return false
        if (isCurrentlyPlaying) return false
        return when (resolve(local, remote, localReadStatus)) {
            Decision.LocalMissing -> true
            Decision.InSync -> {
                val remoteUpdatedAt = remote.lastUpdate ?: return false
                val localUpdatedAt = local?.lastPlayedAt ?: return true
                remoteUpdatedAt > localUpdatedAt
            }
            Decision.Conflict -> isRemoteNewerThanLocal(local, remote)
            Decision.RemoteMissing -> false
        }
    }

    /**
     * Local Upload Freshness Gate (Lets newer device checkpoints overwrite stale server progress)
     * A large position delta alone is not a conflict when the local save happened after the last known ABS checkpoint.
     */
    fun shouldUploadLocalProgress(
        local: BookProgressEntity,
        remote: AbsUserProgressDto?,
        localReadStatus: String? = null
    ): Boolean =
        when (resolve(local, remote, localReadStatus)) {
            Decision.Conflict -> isLocalNewerThanRemote(local, remote)
            Decision.RemoteMissing,
            Decision.InSync,
            Decision.LocalMissing -> true
        }

    /**
     * Remote Position Normalization (Supports both absolute seconds and ratio-duration ABS payloads)
     * authorize.user.mediaProgress can be the startup sync source, so conflict checks must understand the same progress shapes as the mapper.
     */
    private fun AbsUserProgressDto.resolvedPositionMs(): Long? {
        val currentTimeSec = currentTime ?: progress?.let { ratio ->
            duration?.let { totalDurationSec -> ratio * totalDurationSec }
        } ?: return null
        return (currentTimeSec * 1000.0).toLong().coerceAtLeast(0L)
    }

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
