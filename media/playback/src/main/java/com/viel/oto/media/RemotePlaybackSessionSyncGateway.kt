package com.viel.oto.media

import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookProgressEntity

/**
 * Boundary for remote playback-session side effects triggered by local playback progress.
 *
 * Media playback owns when sessions open, sync, and close, while ABS owns how those operations talk
 * to the remote protocol. Keeping the contract here prevents playback runtime from importing ABS
 * implementation classes.
 */
interface RemotePlaybackSessionSyncGateway {
    suspend fun openSession(book: BookEntity, remoteItemId: String)

    suspend fun syncProgress(book: BookEntity, progress: BookProgressEntity, durationMs: Long)

    suspend fun closeSession(book: BookEntity, progress: BookProgressEntity?, durationMs: Long)
}

/**
 * Default inert gateway for builds or tests that do not install a remote-source adapter.
 */
object NoOpRemotePlaybackSessionSyncGateway : RemotePlaybackSessionSyncGateway {
    override suspend fun openSession(book: BookEntity, remoteItemId: String) = Unit

    override suspend fun syncProgress(book: BookEntity, progress: BookProgressEntity, durationMs: Long) = Unit

    override suspend fun closeSession(book: BookEntity, progress: BookProgressEntity?, durationMs: Long) = Unit
}
