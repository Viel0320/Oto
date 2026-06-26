package com.viel.oto.application.library.recovery

import kotlinx.coroutines.flow.Flow

/**
 * Presentation-safe row model for recoverable soft-deleted books.
 * Mirrors the recovery design fields so the UI can render retained metadata without importing Room entities.
 */
data class DeletedBookRecoveryItem(
    val bookId: String,
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val coverPath: String?,
    val coverLastUpdated: Long,
    val progressPercent: Int,
    val sourceLabel: String
)

/**
 * Exhaustive outcome model for restore preflight and confirmation.
 * Gives presentation code typed decisions instead of parsing exception text or storage-layer status strings.
 */
sealed interface DeletedBookRecoveryResult {
    data object RestoredReady : DeletedBookRecoveryResult
    data object RestoredPartial : DeletedBookRecoveryResult
    data object MissingBook : DeletedBookRecoveryResult
    data object MissingRoot : DeletedBookRecoveryResult
    data class RootUnavailable(val reason: String) : DeletedBookRecoveryResult
    data object AbsRemoteDeleted : DeletedBookRecoveryResult
    data object NoAudioFiles : DeletedBookRecoveryResult
    data class AllFilesUnavailable(val reason: String) : DeletedBookRecoveryResult
    data class PartialFilesUnavailable(
        val availableFileIds: List<String>,
        val missingFileIds: List<String>
    ) : DeletedBookRecoveryResult
}

/**
 * Scene-owned stream for recoverable books.
 * Keeps settings UI on a recovery-specific list feed instead of widening the home catalog read model.
 */
interface DeletedBookRecoveryReadModel {
    fun observeRecoverableBooks(): Flow<List<DeletedBookRecoveryItem>>
}

/**
 * Scene-owned restore operations.
 * Separates restore preflight and partial confirmation so cancellation leaves the database untouched.
 */
interface DeletedBookRecoveryCommands {
    suspend fun restoreBook(bookId: String): DeletedBookRecoveryResult
    suspend fun confirmPartialRestore(
        bookId: String,
        availableFileIds: List<String>,
        missingFileIds: List<String>
    ): DeletedBookRecoveryResult
}
