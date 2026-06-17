package com.viel.aplayer.data.cleanup

import com.viel.aplayer.abs.playback.AbsPendingProgressSyncDao
import com.viel.aplayer.abs.playback.AbsPlaybackSessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remote Playback Cleanup Service (Room-backed ABS playback residue adapter)
 *
 * Owns the local persistence cleanup for remote playback state while keeping deletion use cases independent from
 * ABS network syncers and server protocol details.
 */
class RemotePlaybackCleanupGatewayImpl(
    private val absPlaybackSessionDao: AbsPlaybackSessionDao,
    private val absPendingProgressSyncDao: AbsPendingProgressSyncDao
) : RemotePlaybackCleanupGateway {
    override suspend fun deletePlaybackStateForBook(bookId: String) = withContext(Dispatchers.IO) {
        // ABS Session Cleanup (Remove stale server-session state for the deleted book)
        // A soft-deleted ABS audiobook must not leave an active local session row that future playback sync code can reopen or flush.
        absPlaybackSessionDao.deleteByBookId(bookId)

        // ABS Pending Progress Cleanup (Remove unsent progress queued for the deleted book)
        // Pending progress is book-scoped remote state, so deleting the local audiobook must also drop queued uploads for that identifier.
        absPendingProgressSyncDao.deleteByBookId(bookId)
    }
}
