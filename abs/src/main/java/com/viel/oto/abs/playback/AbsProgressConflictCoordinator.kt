package com.viel.oto.abs.playback

import com.viel.oto.abs.mapping.AbsProgressConflictResolver
import com.viel.oto.abs.mapping.AbsProgressMapper
import com.viel.oto.abs.mapping.RemoteProgressReadStatusPolicy
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.progress.ProgressGateway
import com.viel.oto.data.runCatchingCancellable
import java.util.Collections

/**
 * Application service for remote/local progress arbitration.
 * Centralizes remote progress probes, conflict classification, and user-selected authority so playback and upload paths share the same rules.
 */
class AbsProgressConflictCoordinator(
    private val apiClient: AbsApiClient,
    private val bookCatalogGateway: BookCatalogGateway,
    private val bookMetadataGateway: BookMetadataGateway,
    private val progressGateway: ProgressGateway,
    private val credentialProvider: suspend (BookEntity) -> AbsPlaybackSessionSyncer.CredentialSnapshot?,
    private val progressMapper: AbsProgressMapper = AbsProgressMapper(),
    private val conflictResolver: AbsProgressConflictResolver = AbsProgressConflictResolver()
) {
    private val localOverrideBookIds = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Result object consumed by PlayerViewModel before building a playback plan.
     * The UI only reacts to this stable model and never needs to understand ABS transport details or DTO shapes.
     */
    sealed interface PlaybackDecision {
        data object ContinueLocal : PlaybackDecision
        data class ApplyRemote(val conflict: ProgressConflict) : PlaybackDecision
        data class AskUser(val conflict: ProgressConflict) : PlaybackDecision
    }

    /**
     * Separates confirmed conflicts from temporary remote probe failures.
     * Pending retry code uses this distinction to discard stale local uploads only when a real server-vs-device conflict is known.
     */
    enum class UploadDecision {
        Allow,
        Conflict,
        RemoteProbeFailed
    }

    /**
     * Comparable local and remote candidates.
     * Stores both candidates in local Room units so UI prompts and follow-up decisions avoid repeating second-to-millisecond conversion.
     */
    data class ProgressConflict(
        val book: BookEntity,
        val localProgress: BookProgressEntity?,
        val remoteProgress: BookProgressEntity,
        val remoteIsFinished: Boolean?,
        val remoteItemId: String
    )

    /**
     * Fetches remote progress before playback starts.
     * Returns AskUser when the normalized local-vs-remote checkpoints diverge beyond the shared conflict threshold.
     */
    suspend fun preparePlayback(bookId: String): PlaybackDecision {
        val book = bookCatalogGateway.getBookById(bookId) ?: return PlaybackDecision.ContinueLocal
        if (book.sourceType != AudiobookSchema.SourceType.ABS_REMOTE) return PlaybackDecision.ContinueLocal
        val conflict = buildConflictSnapshot(book) ?: return PlaybackDecision.ContinueLocal
        return when (
            conflictResolver.resolveLocalCandidates(
                local = conflict.localProgress,
                remote = conflict.remoteProgress,
                localReadStatus = conflict.book.readStatus,
                remoteIsFinished = conflict.remoteIsFinished
            )
        ) {
            AbsProgressConflictResolver.Decision.InSync -> PlaybackDecision.ContinueLocal
            AbsProgressConflictResolver.Decision.Conflict -> PlaybackDecision.AskUser(conflict)
        }
    }

    /**
     * Marks this playback session as locally authoritative.
     * The mark allows the immediate upload path to overwrite stale ABS progress only after the user explicitly chose the device position.
     */
    fun acceptLocalProgress(bookId: String) {
        localOverrideBookIds += bookId
    }

    /**
     * Persists the remote candidate into local Room before playback plan construction.
     * This makes the existing playback plan builder start from the selected server position without adding ABS-specific plan branches.
     */
    suspend fun acceptRemoteProgress(conflict: ProgressConflict) {
        localOverrideBookIds -= conflict.book.id
        progressGateway.saveProgress(conflict.remoteProgress)
        conflict.remoteIsFinished?.let { isFinished ->
            val nextReadStatus = RemoteProgressReadStatusPolicy.fromRemoteProgress(
                isFinished = isFinished,
                hasPositivePosition = conflict.remoteProgress.globalPositionMs > 0L
            )
            bookMetadataGateway.updateBookReadStatus(conflict.book.id, nextReadStatus)
        }
    }

    /**
     * Ends a user-confirmed local authority window.
     * Called when a remote session closes so a future playback start must compare progress again instead of silently reusing old consent.
     */
    fun clearLocalOverride(bookId: String) {
        localOverrideBookIds -= bookId
    }

    /**
     * Prevents background sync from overwriting divergent server progress without consent.
     * Remote absence and near-equal positions allow upload; divergent positions require a user-confirmed local override.
     */
    suspend fun shouldUploadLocalProgress(
        book: BookEntity,
        localProgress: BookProgressEntity,
        credential: AbsPlaybackSessionSyncer.CredentialSnapshot
    ): Boolean = resolveUploadDecision(book, localProgress, credential) == UploadDecision.Allow

    /**
     * Fetches the current server checkpoint before any local progress upload.
     * This method avoids collapsing probe failures and true conflicts into the same boolean result for retry handling.
     */
    suspend fun resolveUploadDecision(
        book: BookEntity,
        localProgress: BookProgressEntity,
        credential: AbsPlaybackSessionSyncer.CredentialSnapshot
    ): UploadDecision {
        if (book.sourceType != AudiobookSchema.SourceType.ABS_REMOTE) return UploadDecision.Allow
        if (localOverrideBookIds.contains(book.id)) return UploadDecision.Allow
        val remoteItemId = remoteItemId(book) ?: return UploadDecision.Allow
        val remote = runCatchingCancellable {
            apiClient.getProgressOrNull(
                baseUrl = credential.baseUrl,
                token = credential.token,
                itemId = remoteItemId
            )
        }.getOrElse {
            return UploadDecision.RemoteProbeFailed
        }
        return if (conflictResolver.shouldUploadLocalProgress(localProgress, remote, book.readStatus)) {
            UploadDecision.Allow
        } else {
            UploadDecision.Conflict
        }
    }

    private suspend fun buildConflictSnapshot(book: BookEntity): ProgressConflict? {
        val remoteItemId = remoteItemId(book) ?: return null
        val credential = credentialProvider(book) ?: return null
        val remote = runCatchingCancellable {
            apiClient.getProgressOrNull(
                baseUrl = credential.baseUrl,
                token = credential.token,
                itemId = remoteItemId
            )
        }.getOrNull() ?: return null
        val files = bookCatalogGateway.getFilesForBookSync(book.id)
        val local = progressGateway.getProgressForBookSync(book.id)
        return ProgressConflict(
            book = book,
            localProgress = local,
            remoteProgress = progressMapper.toProgress(remote, book, files, System.currentTimeMillis()),
            remoteIsFinished = remote.isFinished,
            remoteItemId = remoteItemId
        )
    }

    private fun remoteItemId(book: BookEntity): String? =
        book.id.substringAfter(":item:", missingDelimiterValue = "")
            .takeIf { itemId -> itemId.isNotBlank() }
}
