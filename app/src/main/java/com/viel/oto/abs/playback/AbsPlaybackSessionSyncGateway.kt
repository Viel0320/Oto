package com.viel.oto.abs.playback

import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.media.RemotePlaybackSessionSyncGateway

/**
 * ABS adapter for the playback module's remote-session boundary.
 *
 * Playback stays coupled only to the gateway contract, while this adapter keeps ABS protocol
 * session semantics inside the ABS package.
 */
class AbsPlaybackSessionSyncGateway(
    private val syncer: AbsPlaybackSessionSyncer
) : RemotePlaybackSessionSyncGateway {
    override suspend fun openSession(book: BookEntity, remoteItemId: String) {
        syncer.openSession(book, remoteItemId)
    }

    override suspend fun syncProgress(book: BookEntity, progress: BookProgressEntity, durationMs: Long) {
        syncer.syncProgress(book, progress, durationMs)
    }

    override suspend fun closeSession(book: BookEntity, progress: BookProgressEntity?, durationMs: Long) {
        syncer.closeSession(book, progress, durationMs)
    }
}
