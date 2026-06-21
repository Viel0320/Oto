package com.viel.aplayer.data.cleanup

import com.viel.aplayer.abs.playback.AbsPendingProgressSyncDao
import com.viel.aplayer.abs.playback.AbsPlaybackSessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed ABS playback residue adapter.
 *
 * Owns the local persistence cleanup for remote playback state while keeping deletion use cases independent from
 * ABS network syncers and server protocol details.
 */
class RemotePlaybackCleanupGatewayImpl(
    private val absPlaybackSessionDao: AbsPlaybackSessionDao,
    private val absPendingProgressSyncDao: AbsPendingProgressSyncDao
) : RemotePlaybackCleanupGateway {
    override suspend fun deletePlaybackStateForBook(bookId: String) = withContext(Dispatchers.IO) {
        absPlaybackSessionDao.deleteByBookId(bookId)

        absPendingProgressSyncDao.deleteByBookId(bookId)
    }
}
