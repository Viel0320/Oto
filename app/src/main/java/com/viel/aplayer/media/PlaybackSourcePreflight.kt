package com.viel.aplayer.media

import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.network.UnsafeNetworkPolicy
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
            // Playback Cleartext Root Gate (Blocks HTTP-backed remote roots before MediaController receives media items)
            // VFS playback URIs hide provider URLs, so the preflight checks persisted root endpoints instead of relying on generated media item schemes.
            if (!UnsafeNetworkPolicy.isCleartextHttpAllowed(root.sourceUri, settings)) {
                return@withContext PlaybackSourcePreflightResult.CleartextHttpBlocked
            }
        }
        PlaybackSourcePreflightResult.Available
    }
}

/**
 * Playback Source Preflight Result (Represents a DB-only gate decision)
 * Keeps the caller independent from Room entities while preserving a typed block reason for localized feedback mapping.
 */
sealed class PlaybackSourcePreflightResult {
    data object Available : PlaybackSourcePreflightResult()
    data class Blocked(
        val reason: PlaybackSourcePreflightBlockReason,
        val rootName: String? = null
    ) : PlaybackSourcePreflightResult()
    // Cleartext HTTP Blocked (Distinguishes security-policy blocks from unavailable storage roots)
    // PlaybackManager maps this result to the existing CleartextPlaybackBlocked domain event so the app shell can render the dedicated feedback message.
    data object CleartextHttpBlocked : PlaybackSourcePreflightResult()
}

/**
 * Playback Source Preflight Block Reason (Stable media-core block code)
 * The app-shell event bridge maps these codes to localized feedback instead of receiving preformatted text from playback.
 */
enum class PlaybackSourcePreflightBlockReason {
    MissingRoot,
    UnavailableRoot
}
