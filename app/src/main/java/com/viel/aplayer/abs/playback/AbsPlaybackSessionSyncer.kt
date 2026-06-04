package com.viel.aplayer.abs.playback

import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsDeviceInfoDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.logger.AbsPlaybackLogger

class AbsPlaybackSessionSyncer(
    private val apiClient: AbsApiClient,
    private val absPlaybackSessionDao: AbsPlaybackSessionDao,
    private val absPendingProgressSyncDao: AbsPendingProgressSyncDao,
    private val catalogStore: AbsCatalogStore,
    private val credentialProvider: suspend (BookEntity) -> CredentialSnapshot?
) {
    suspend fun openSession(book: BookEntity, remoteItemId: String) {
        if (book.sourceType != AudiobookSchema.SourceType.ABS_REMOTE) {
            AbsPlaybackLogger.logOpenSessionSkipped(bookId = book.id, reason = "NOT_ABS_REMOTE")
            return
        }
        val credential = credentialProvider(book)
        if (credential == null) {
            AbsPlaybackLogger.logOpenSessionSkipped(bookId = book.id, reason = "MISSING_CREDENTIAL")
            return
        }
        val start = AbsPlaybackLogger.mark()
        AbsPlaybackLogger.logOpenSessionStart(bookId = book.id, remoteItemId = remoteItemId)
        val response = apiClient.openPlaybackSession(
            baseUrl = credential.baseUrl,
            token = credential.token,
            itemId = remoteItemId,
            request = AbsPlayRequestDto(
                deviceInfo = AbsDeviceInfoDto(clientName = "APlayer", deviceId = "aplayer-local")
            )
        )
        absPlaybackSessionDao.insertOrReplace(
            AbsPlaybackSessionEntity(
                bookId = book.id,
                remoteItemId = remoteItemId,
                sessionId = requireNotNull(response.id),
                currentTimeSec = 0.0,
                timeListenedSec = 0.0,
                state = "OPEN"
            )
        )
        AbsPlaybackLogger.logOpenSessionSuccess(
            bookId = book.id,
            remoteItemId = remoteItemId,
            sessionId = requireNotNull(response.id),
            costMs = AbsPlaybackLogger.elapsedMs(start)
        )
        flushPendingIfAny(book.id, credential)
    }

    suspend fun syncProgress(book: BookEntity, progress: BookProgressEntity, durationMs: Long) {
        if (book.sourceType != AudiobookSchema.SourceType.ABS_REMOTE) return
        val session = absPlaybackSessionDao.getByBookId(book.id) ?: return
        val credential = credentialProvider(book) ?: return
        val currentTimeSec = progress.globalPositionMs / 1000.0
        val timeListenedSec = currentTimeSec
        val start = AbsPlaybackLogger.mark()
        AbsPlaybackLogger.logSyncStart(
            bookId = book.id,
            sessionId = session.sessionId,
            currentTimeSec = currentTimeSec,
            durationSec = durationMs / 1000.0
        )
        runCatching {
            apiClient.syncSession(
                baseUrl = credential.baseUrl,
                token = credential.token,
                sessionId = session.sessionId,
                currentTimeSec = currentTimeSec,
                timeListenedSec = timeListenedSec,
                durationSec = durationMs / 1000.0
            )
            absPlaybackSessionDao.insertOrReplace(
                session.copy(
                    currentTimeSec = currentTimeSec,
                    timeListenedSec = timeListenedSec,
                    state = "SYNCED"
                )
            )
            AbsPlaybackLogger.logSyncSuccess(
                bookId = book.id,
                sessionId = session.sessionId,
                costMs = AbsPlaybackLogger.elapsedMs(start)
            )
        }.onFailure {
            absPendingProgressSyncDao.insertOrReplace(
                AbsPendingProgressSyncEntity(
                    bookId = book.id,
                    remoteItemId = session.remoteItemId,
                    currentTimeSec = currentTimeSec,
                    timeListenedSec = timeListenedSec,
                    durationSec = durationMs / 1000.0,
                    updatedAt = System.currentTimeMillis()
                )
            )
            // Local Progress Truth (Log sync state as pending on network failure)
            // When remote synchronization fails, the local playback progress remains the single source of truth.
            // Therefore, the event is logged as "pending" rather than a hard failure to reflect that progress will be retried later.
            AbsPlaybackLogger.logSyncPending(
                bookId = book.id,
                sessionId = session.sessionId,
                costMs = AbsPlaybackLogger.elapsedMs(start),
                errorClass = it::class.java.simpleName,
                message = it.message
            )
        }
    }

    suspend fun closeSession(book: BookEntity, progress: BookProgressEntity?, durationMs: Long) {
        if (book.sourceType != AudiobookSchema.SourceType.ABS_REMOTE) return
        val session = absPlaybackSessionDao.getByBookId(book.id) ?: return
        val credential = credentialProvider(book) ?: return
        val currentTimeSec = (progress?.globalPositionMs ?: 0L) / 1000.0
        val timeListenedSec = currentTimeSec
        val start = AbsPlaybackLogger.mark()
        AbsPlaybackLogger.logCloseStart(
            bookId = book.id,
            sessionId = session.sessionId,
            currentTimeSec = currentTimeSec,
            durationSec = durationMs / 1000.0
        )
        runCatching {
            apiClient.closeSession(
                baseUrl = credential.baseUrl,
                token = credential.token,
                sessionId = session.sessionId,
                currentTimeSec = currentTimeSec,
                timeListenedSec = timeListenedSec,
                durationSec = durationMs / 1000.0
            )
            AbsPlaybackLogger.logCloseSuccess(
                bookId = book.id,
                sessionId = session.sessionId,
                costMs = AbsPlaybackLogger.elapsedMs(start)
            )
        }.onFailure {
            absPendingProgressSyncDao.insertOrReplace(
                AbsPendingProgressSyncEntity(
                    bookId = book.id,
                    remoteItemId = session.remoteItemId,
                    currentTimeSec = currentTimeSec,
                    timeListenedSec = timeListenedSec,
                    durationSec = durationMs / 1000.0,
                    updatedAt = System.currentTimeMillis()
                )
            )
            AbsPlaybackLogger.logClosePending(
                bookId = book.id,
                sessionId = session.sessionId,
                costMs = AbsPlaybackLogger.elapsedMs(start),
                errorClass = it::class.java.simpleName,
                message = it.message
            )
        }
        absPlaybackSessionDao.deleteByBookId(book.id)
    }

    private suspend fun flushPendingIfAny(bookId: String, credential: CredentialSnapshot) {
        val pending = absPendingProgressSyncDao.getByBookId(bookId)
        if (pending == null) {
            AbsPlaybackLogger.logFlushPendingSkipped(bookId = bookId, reason = "NO_PENDING")
            return
        }
        val session = absPlaybackSessionDao.getByBookId(bookId)
        if (session == null) {
            AbsPlaybackLogger.logFlushPendingSkipped(bookId = bookId, reason = "NO_SESSION")
            return
        }
        val start = AbsPlaybackLogger.mark()
        AbsPlaybackLogger.logFlushPendingStart(bookId = bookId, sessionId = session.sessionId)
        runCatching {
            apiClient.syncSession(
                baseUrl = credential.baseUrl,
                token = credential.token,
                sessionId = session.sessionId,
                currentTimeSec = pending.currentTimeSec,
                timeListenedSec = pending.timeListenedSec,
                durationSec = pending.durationSec
            )
            absPendingProgressSyncDao.deleteByBookId(bookId)
            AbsPlaybackLogger.logFlushPendingSuccess(
                bookId = bookId,
                sessionId = session.sessionId,
                costMs = AbsPlaybackLogger.elapsedMs(start)
            )
        }.onFailure { error ->
            AbsPlaybackLogger.logFlushPendingFailure(
                bookId = bookId,
                sessionId = session.sessionId,
                costMs = AbsPlaybackLogger.elapsedMs(start),
                errorClass = error::class.java.simpleName,
                message = error.message
            )
        }
    }

    data class CredentialSnapshot(
        val baseUrl: String,
        val token: String
    )
}
