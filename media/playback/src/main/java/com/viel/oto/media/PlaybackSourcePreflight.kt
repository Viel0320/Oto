package com.viel.oto.media

import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.shared.policy.UnsafeNetworkPolicy
import com.viel.oto.shared.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Blocks media loading when persisted root state is already unavailable.
 * Reads only library root rows from the local database so playback startup never performs network probes, SAF traversal, or file-open checks before the user actually enters media loading.
 */
class PlaybackSourcePreflight(
    private val libraryRootDao: LibraryRootDao
) {
    /**
     * Checks persisted root lifecycle state before media source creation.
     * Ensures a playback plan is allowed to reach MediaController only when all referenced library roots still exist and remain ACTIVE in Room.
     */
    suspend fun check(plan: BookPlaybackPlan, settings: AppSettings): PlaybackSourcePreflightResult = withContext(Dispatchers.IO) {
        val rootIds = plan.files.map { file -> file.rootId }.distinct()
        for (rootId in rootIds) {
            val root = libraryRootDao.getRootById(rootId)
                ?: return@withContext PlaybackSourcePreflightResult.Blocked(
                    reason = PlaybackSourcePreflightBlockReason.MissingRoot
                )
            if (root.status != AudiobookSchema.LibraryRootStatus.ACTIVE) {
                val rootName = root.displayName.ifBlank { root.sourceUri }
                return@withContext PlaybackSourcePreflightResult.Blocked(
                    reason = PlaybackSourcePreflightBlockReason.UnavailableRoot,
                    rootName = rootName
                )
            }
            if (!UnsafeNetworkPolicy.isCleartextHttpAllowed(root.sourceUri, settings)) {
                return@withContext PlaybackSourcePreflightResult.CleartextHttpBlocked
            }
        }
        PlaybackSourcePreflightResult.Available
    }
}

/**
 * Represents a DB-only gate decision.
 * Keeps the caller independent from Room entities while preserving a typed block reason for localized feedback mapping.
 */
sealed class PlaybackSourcePreflightResult {
    data object Available : PlaybackSourcePreflightResult()
    data class Blocked(
        val reason: PlaybackSourcePreflightBlockReason,
        val rootName: String? = null
    ) : PlaybackSourcePreflightResult()
    data object CleartextHttpBlocked : PlaybackSourcePreflightResult()
}

/**
 * Stable media-core block code.
 * The app-shell event bridge maps these codes to localized feedback instead of receiving preformatted text from playback.
 */
enum class PlaybackSourcePreflightBlockReason {
    MissingRoot,
    UnavailableRoot
}
