package com.viel.aplayer.abs.mapping

import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.data.entity.BookProgressEntity

class AbsProgressConflictResolver {
    fun shouldApplyRemoteProgress(
        local: BookProgressEntity?,
        remote: AbsUserProgressDto?,
        isCurrentlyPlaying: Boolean
    ): Boolean {
        if (remote == null) return false
        if (isCurrentlyPlaying) return false
        val remoteUpdatedAt = remote.lastUpdate ?: return false
        val localUpdatedAt = local?.lastPlayedAt ?: return true
        return remoteUpdatedAt > localUpdatedAt
    }
}
