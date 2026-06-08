package com.viel.aplayer.abs.playback

import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsDeviceInfoDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.runCatchingCancellable
import com.viel.aplayer.logger.AbsPlaybackLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AbsPlaybackSessionSyncer(
    private val apiClient: AbsApiClient,
    private val absPlaybackSessionDao: AbsPlaybackSessionDao,
    private val absPendingProgressSyncDao: AbsPendingProgressSyncDao,
    private val catalogStore: AbsCatalogStore,
    private val credentialProvider: suspend (BookEntity) -> CredentialSnapshot?,
    private val progressConflictCoordinator: AbsProgressConflictCoordinator? = null
) {
    // ABS Session Operation Mutex (Serializes open, sync, and close calls for one playback syncer)
    // Playback controls can issue pause, stop, switch-book, and progress sync requests concurrently; this lock preserves server session ordering without blocking local player commands.
    private val sessionOperationMutex = Mutex()

    suspend fun openSession(book: BookEntity, remoteItemId: String) = sessionOperationMutex.withLock {
        openSessionLocked(book, remoteItemId)
    }

    private suspend fun openSessionLocked(book: BookEntity, remoteItemId: String) {
        if (book.sourceType != AudiobookSchema.SourceType.ABS_REMOTE) {
            AbsPlaybackLogger.logOpenSessionSkipped(bookId = book.id, reason = "NOT_ABS_REMOTE")
            return
        }
        if (absPlaybackSessionDao.getByBookId(book.id) != null) {
            // Idempotent Session Open (Avoids creating duplicate server sessions during resume or repeated play commands)
            // PlaybackManager may ask to ensure an ABS session after local play resumes; an existing local session row means the remote session is already active.
            AbsPlaybackLogger.logOpenSessionSkipped(bookId = book.id, reason = "SESSION_ALREADY_OPEN")
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
        flushPendingIfAny(book, credential)
    }

    suspend fun syncProgress(book: BookEntity, progress: BookProgressEntity, durationMs: Long) = sessionOperationMutex.withLock {
        syncProgressLocked(book, progress, durationMs)
    }

    private suspend fun syncProgressLocked(book: BookEntity, progress: BookProgressEntity, durationMs: Long) {
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
        when (progressConflictCoordinator?.resolveUploadDecision(
            book = book,
            localProgress = progress,
            credential = credential
        ) ?: AbsProgressConflictCoordinator.UploadDecision.Allow) {
            AbsProgressConflictCoordinator.UploadDecision.Allow -> Unit
            AbsProgressConflictCoordinator.UploadDecision.Conflict -> {
                AbsPlaybackLogger.logSyncSkipped(
                    bookId = book.id,
                    sessionId = session.sessionId,
                    reason = "REMOTE_PROGRESS_CONFLICT"
                )
                return
            }
            AbsProgressConflictCoordinator.UploadDecision.RemoteProbeFailed -> {
                insertPendingProgress(
                    bookId = book.id,
                    remoteItemId = session.remoteItemId,
                    currentTimeSec = currentTimeSec,
                    timeListenedSec = timeListenedSec,
                    durationSec = durationMs / 1000.0
                )
                AbsPlaybackLogger.logSyncSkipped(
                    bookId = book.id,
                    sessionId = session.sessionId,
                    reason = "REMOTE_PROGRESS_PROBE_FAILED"
                )
                return
            }
        }
        runCatchingCancellable {
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
            insertPendingProgress(
                bookId = book.id,
                remoteItemId = session.remoteItemId,
                currentTimeSec = currentTimeSec,
                timeListenedSec = timeListenedSec,
                durationSec = durationMs / 1000.0
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

    suspend fun closeSession(book: BookEntity, progress: BookProgressEntity?, durationMs: Long) = sessionOperationMutex.withLock {
        closeSessionLocked(book, progress, durationMs)
    }

    private suspend fun closeSessionLocked(book: BookEntity, progress: BookProgressEntity?, durationMs: Long) {
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
        when (progress?.let { localProgress ->
            progressConflictCoordinator?.resolveUploadDecision(
                book = book,
                localProgress = localProgress,
                credential = credential
            )
        } ?: AbsProgressConflictCoordinator.UploadDecision.Allow) {
            AbsProgressConflictCoordinator.UploadDecision.Allow -> Unit
            AbsProgressConflictCoordinator.UploadDecision.Conflict -> {
                AbsPlaybackLogger.logCloseSkipped(
                    bookId = book.id,
                    sessionId = session.sessionId,
                    reason = "REMOTE_PROGRESS_CONFLICT"
                )
                absPlaybackSessionDao.deleteByBookId(book.id)
                progressConflictCoordinator?.clearLocalOverride(book.id)
                return
            }
            AbsProgressConflictCoordinator.UploadDecision.RemoteProbeFailed -> {
                insertPendingProgress(
                    bookId = book.id,
                    remoteItemId = session.remoteItemId,
                    currentTimeSec = currentTimeSec,
                    timeListenedSec = timeListenedSec,
                    durationSec = durationMs / 1000.0
                )
                AbsPlaybackLogger.logCloseSkipped(
                    bookId = book.id,
                    sessionId = session.sessionId,
                    reason = "REMOTE_PROGRESS_PROBE_FAILED"
                )
                absPlaybackSessionDao.deleteByBookId(book.id)
                progressConflictCoordinator?.clearLocalOverride(book.id)
                return
            }
        }
        runCatchingCancellable {
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
            insertPendingProgress(
                bookId = book.id,
                remoteItemId = session.remoteItemId,
                currentTimeSec = currentTimeSec,
                timeListenedSec = timeListenedSec,
                durationSec = durationMs / 1000.0
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
        progressConflictCoordinator?.clearLocalOverride(book.id)
    }

    private suspend fun flushPendingIfAny(book: BookEntity, credential: CredentialSnapshot) {
        val bookId = book.id
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
        when (val decision = progressConflictCoordinator?.resolveUploadDecision(
            book = book,
            localProgress = pending.toProgressEntity(),
            credential = credential
        ) ?: AbsProgressConflictCoordinator.UploadDecision.Allow) {
            AbsProgressConflictCoordinator.UploadDecision.Allow -> Unit
            AbsProgressConflictCoordinator.UploadDecision.Conflict -> {
                // Pending Conflict Disposal (Drops stale retry data after confirming the server has diverged)
                // A pending upload represents an old automatic retry, so once a conflict is known it must not overwrite newer server progress.
                absPendingProgressSyncDao.deleteByBookId(bookId)
                AbsPlaybackLogger.logFlushPendingSkipped(bookId = bookId, reason = "REMOTE_PROGRESS_CONFLICT")
                return
            }
            AbsProgressConflictCoordinator.UploadDecision.RemoteProbeFailed -> {
                AbsPlaybackLogger.logFlushPendingSkipped(bookId = bookId, reason = "REMOTE_PROGRESS_PROBE_FAILED")
                return
            }
        }
        val start = AbsPlaybackLogger.mark()
        AbsPlaybackLogger.logFlushPendingStart(bookId = bookId, sessionId = session.sessionId)
        runCatchingCancellable {
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

    /**
     * Pending Progress Insert (Persists local upload attempts that could not be safely sent)
     * This helper keeps sync, close, and probe-failure paths writing identical retry payloads.
     */
    private suspend fun insertPendingProgress(
        bookId: String,
        remoteItemId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        durationSec: Double
    ) {
        absPendingProgressSyncDao.insertOrReplace(
            AbsPendingProgressSyncEntity(
                bookId = bookId,
                remoteItemId = remoteItemId,
                currentTimeSec = currentTimeSec,
                timeListenedSec = timeListenedSec,
                durationSec = durationSec,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Pending Progress Projection (Reconstructs a local checkpoint for retry arbitration)
     * Pending rows only store ABS seconds, so the conflict coordinator receives a minimal progress entity in local millisecond units.
     */
    private fun AbsPendingProgressSyncEntity.toProgressEntity(): BookProgressEntity =
        BookProgressEntity(
            bookId = bookId,
            globalPositionMs = (currentTimeSec * 1000.0).toLong().coerceAtLeast(0L),
            lastPlayedAt = updatedAt
        )

    data class CredentialSnapshot(
        val baseUrl: String,
        val token: String
    )
}
