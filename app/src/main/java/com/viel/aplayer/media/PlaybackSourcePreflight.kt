package com.viel.aplayer.media

import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playback Source Preflight (Blocks media loading when persisted root state is already unavailable)
 * Reads only library root rows from the local database so playback startup never performs network probes, SAF traversal, or file-open checks before the user actually enters media loading.
 */
class PlaybackSourcePreflight(
    private val libraryRootDao: LibraryRootDao
) {
    /**
     * Validate Playback Roots (Checks persisted root lifecycle state before media source creation)
     * Ensures a playback plan is allowed to reach MediaController only when all referenced library roots still exist and remain ACTIVE in Room.
     */
    suspend fun check(plan: BookPlaybackPlan): PlaybackSourcePreflightResult = withContext(Dispatchers.IO) {
        val rootIds = plan.files.map { file -> file.rootId }.distinct()
        for (rootId in rootIds) {
            val root = libraryRootDao.getRootById(rootId)
                ?: return@withContext PlaybackSourcePreflightResult.Blocked("媒体库根不存在，无法载入媒体源")
            if (root.status != AudiobookSchema.LibraryRootStatus.ACTIVE) {
                val rootName = root.displayName.ifBlank { root.sourceUri }
                return@withContext PlaybackSourcePreflightResult.Blocked("媒体库根不可用，无法载入媒体源：$rootName")
            }
        }
        PlaybackSourcePreflightResult.Available
    }
}

/**
 * Playback Source Preflight Result (Represents a DB-only gate decision)
 * Keeps the caller independent from Room entities while preserving a user-facing block message for immediate feedback.
 */
sealed class PlaybackSourcePreflightResult {
    data object Available : PlaybackSourcePreflightResult()
    data class Blocked(val message: String) : PlaybackSourcePreflightResult()
}
