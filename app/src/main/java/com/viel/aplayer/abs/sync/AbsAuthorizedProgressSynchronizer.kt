package com.viel.aplayer.abs.sync

import com.viel.aplayer.abs.mapping.AbsProgressConflictResolver
import com.viel.aplayer.abs.mapping.AbsProgressMapper
import com.viel.aplayer.abs.mapping.AbsRemoteIdMapper
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.ProgressGateway

/**
 * ABS Authorized Progress Synchronizer (Merges authorize-scoped user progress for any sync trigger)
 * Startup, manual catalog sync, and background workers can reuse the same full user progress snapshot path without opening playback sessions.
 */
class AbsAuthorizedProgressSynchronizer(
    private val apiClient: AbsApiClient,
    private val credentialProvider: suspend (LibraryRootEntity) -> CredentialSnapshot?,
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val progressMapper: AbsProgressMapper = AbsProgressMapper(),
    private val conflictResolver: AbsProgressConflictResolver = AbsProgressConflictResolver(),
    private val idMapper: AbsRemoteIdMapper = AbsRemoteIdMapper(),
    private val isCurrentlyPlaying: (String) -> Boolean = { false }
) {
    /**
     * Sync Root Batch (Processes every registered ABS root as an independent best-effort batch)
     * A failed root is counted and skipped so one offline server cannot block other ABS accounts in startup-wide refreshes.
     */
    suspend fun sync(roots: List<LibraryRootEntity>): AbsAuthorizedProgressSyncSummary {
        var summary = AbsAuthorizedProgressSyncSummary()
        roots.filter { root -> root.sourceType == AudiobookSchema.LibrarySourceType.ABS }
            .forEach { root ->
                val result = runCatching {
                    syncRoot(root)
                }
                summary += result.getOrElse {
                    AbsAuthorizedProgressSyncSummary(failedRootCount = 1)
                }
            }
        return summary
    }

    /**
     * Sync Root Progress (Fetches the full authorize progress list once per root)
     * authorize.user.mediaProgress is already user-scoped, so callers avoid per-item progress probes while still using the shared conflict resolver.
     */
    suspend fun syncRoot(root: LibraryRootEntity): AbsAuthorizedProgressSyncSummary {
        if (root.sourceType != AudiobookSchema.LibrarySourceType.ABS) {
            return AbsAuthorizedProgressSyncSummary()
        }
        val credential = credentialProvider(root) ?: return AbsAuthorizedProgressSyncSummary()
        val authorization = apiClient.authorize(credential.baseUrl, credential.token)
        return mergeAuthorizedProgress(
            root = root,
            baseUrl = credential.baseUrl,
            userId = authorization.user?.id,
            mediaProgress = authorization.user?.mediaProgress.orEmpty()
        )
    }

    /**
     * Merge Authorized Progress Snapshot (Reuses an already fetched authorize.user.mediaProgress payload)
     * Catalog synchronization already authorizes the root, so this entry point lets existing sync flows share the merger without a duplicate network call.
     */
    suspend fun mergeAuthorizedProgress(
        root: LibraryRootEntity,
        baseUrl: String,
        userId: String?,
        mediaProgress: List<AbsUserProgressDto>
    ): AbsAuthorizedProgressSyncSummary {
        if (root.sourceType != AudiobookSchema.LibrarySourceType.ABS) {
            return AbsAuthorizedProgressSyncSummary()
        }
        val serverKey = idMapper.serverKey(baseUrl, userId)
        var summary = AbsAuthorizedProgressSyncSummary()
        mediaProgress.forEach { remote ->
            summary += AbsAuthorizedProgressSyncSummary(remoteProgressCount = 1)
            summary += mergeRemoteProgress(root, serverKey, remote)
        }
        return summary
    }

    /**
     * Merge Remote Progress (Maps an ABS library item checkpoint onto an existing mirrored local book)
     * Unknown items are skipped because catalog synchronization owns book creation and track metadata materialization.
     */
    private suspend fun mergeRemoteProgress(
        root: LibraryRootEntity,
        serverKey: String,
        remote: AbsUserProgressDto
    ): AbsAuthorizedProgressSyncSummary {
        val itemId = remote.libraryItemId?.takeIf { it.isNotBlank() } ?: return AbsAuthorizedProgressSyncSummary(skippedMissingBookCount = 1)
        val bookId = idMapper.bookId(serverKey, itemId)
        val book = bookQueryGateway.getBookById(bookId)
            ?.takeIf { existing -> existing.rootId == root.id && existing.sourceType == AudiobookSchema.SourceType.ABS_REMOTE }
            ?: return AbsAuthorizedProgressSyncSummary(skippedMissingBookCount = 1)
        val localProgress = progressGateway.getProgressForBookSync(book.id)
        val shouldApply = conflictResolver.shouldApplyRemoteProgress(
            local = localProgress,
            remote = remote,
            isCurrentlyPlaying = isCurrentlyPlaying(book.id),
            localReadStatus = book.readStatus
        )
        if (!shouldApply) {
            return AbsAuthorizedProgressSyncSummary(skippedByResolverCount = 1)
        }
        applyRemoteProgress(book, remote)
        return AbsAuthorizedProgressSyncSummary(appliedCount = 1)
    }

    /**
     * Apply Remote Progress (Persists the resolver-approved checkpoint and aligns readStatus)
     * ProgressGateway stores the physical file anchor, while BookQueryGateway updates semantic reading state only when it actually changes.
     */
    private suspend fun applyRemoteProgress(book: BookEntity, remote: AbsUserProgressDto) {
        val files = bookQueryGateway.getFilesForBookSync(book.id)
        progressGateway.saveProgress(progressMapper.toProgress(remote, book, files, System.currentTimeMillis()))
        val nextReadStatus = when {
            remote.isFinished == true -> AudiobookSchema.ReadStatus.FINISHED
            progressMapper.resolvedCurrentTimeSec(remote) > 0.0 -> AudiobookSchema.ReadStatus.IN_PROGRESS
            else -> AudiobookSchema.ReadStatus.NOT_STARTED
        }
        if (book.readStatus != nextReadStatus) {
            bookQueryGateway.updateBookReadStatus(book.id, nextReadStatus)
        }
    }

    /**
     * Root Credential Snapshot (Carries the minimum authorize data required for root-wide progress refresh)
     * This keeps authorized progress synchronization independent from playback session lifecycle models.
     */
    data class CredentialSnapshot(
        val baseUrl: String,
        val token: String
    )
}

/**
 * Authorized Progress Sync Summary (Reports user progress merge outcomes without surfacing transport details)
 * Startup warmup, manual sync, and background workers can log the same compact aggregate across different triggers.
 */
data class AbsAuthorizedProgressSyncSummary(
    val remoteProgressCount: Int = 0,
    val appliedCount: Int = 0,
    val skippedMissingBookCount: Int = 0,
    val skippedByResolverCount: Int = 0,
    val failedRootCount: Int = 0
)

/**
 * Authorized Progress Summary Merge (Combines per-root outcomes into a single aggregate)
 * Keeping aggregation beside the summary model prevents callers from duplicating counter math.
 */
private operator fun AbsAuthorizedProgressSyncSummary.plus(other: AbsAuthorizedProgressSyncSummary): AbsAuthorizedProgressSyncSummary =
    AbsAuthorizedProgressSyncSummary(
        remoteProgressCount = remoteProgressCount + other.remoteProgressCount,
        appliedCount = appliedCount + other.appliedCount,
        skippedMissingBookCount = skippedMissingBookCount + other.skippedMissingBookCount,
        skippedByResolverCount = skippedByResolverCount + other.skippedByResolverCount,
        failedRootCount = failedRootCount + other.failedRootCount
    )
