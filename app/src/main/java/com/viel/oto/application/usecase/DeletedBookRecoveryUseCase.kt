package com.viel.oto.application.usecase

import com.viel.oto.abs.sync.AbsItemMirrorEntity
import com.viel.oto.application.library.recovery.DeletedBookRecoveryItem
import com.viel.oto.application.library.recovery.DeletedBookRecoveryResult
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.availability.AvailabilityResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Persistence seam used by the recovery use case.
 * Provides only soft-delete recovery reads and status writes so the use case stays independent of broader catalog gateways.
 */
interface DeletedBookRecoveryStore {
    fun observeRecoverableBooks(): Flow<List<DeletedBookRecoveryItem>>
    suspend fun getBook(bookId: String): BookEntity?
    suspend fun getRoot(rootId: String): LibraryRootEntity?
    suspend fun getAudioFiles(bookId: String): List<BookFileEntity>
    suspend fun getAbsMirror(bookId: String): AbsItemMirrorEntity?
    suspend fun restoreReady(bookId: String, readyFileIds: List<String>): Boolean
    suspend fun restorePartial(bookId: String, readyFileIds: List<String>, missingFileIds: List<String>): Boolean
}

/**
 * Coordinates soft-delete restore preflight and writes.
 * Checks book state, root availability, ABS mirror state, and audio file reachability before changing local visibility.
 */
class DeletedBookRecoveryUseCase(
    private val store: DeletedBookRecoveryStore,
    private val checkRootAvailability: suspend (LibraryRootEntity) -> AvailabilityResult,
    private val checkAudioFilesAvailability: suspend (List<BookFileEntity>) -> Map<String, AvailabilityResult>
) {
    fun observeRecoverableBooks(): Flow<List<DeletedBookRecoveryItem>> =
        store.observeRecoverableBooks()

    /**
     * Runs recoverability checks before any Room writes.
     * Returns a partial confirmation result when only some audio rows are reachable, leaving persistence unchanged until confirmation.
     */
    suspend fun restoreBook(bookId: String): DeletedBookRecoveryResult = withContext(Dispatchers.IO) {
        val book = store.getBook(bookId).takeIf { it?.status == AudiobookSchema.BookStatus.DELETED }
            ?: return@withContext DeletedBookRecoveryResult.MissingBook
        val root = store.getRoot(book.rootId)
            ?: return@withContext DeletedBookRecoveryResult.MissingRoot
        val rootAvailability = checkRootAvailability(root)
        if (!rootAvailability.isAvailable) {
            return@withContext DeletedBookRecoveryResult.RootUnavailable(rootAvailability.recoveryReason())
        }
        val mirror = store.getAbsMirror(bookId)
        if (mirror?.state == AudiobookSchema.AbsMirrorState.REMOTE_DELETED) {
            return@withContext DeletedBookRecoveryResult.AbsRemoteDeleted
        }
        val audioFiles = store.getAudioFiles(bookId)
        if (audioFiles.isEmpty()) {
            return@withContext DeletedBookRecoveryResult.NoAudioFiles
        }
        val availabilityByFileId = checkAudioFilesAvailability(audioFiles)
        val availableFileIds = audioFiles
            .filter { file -> availabilityByFileId[file.id]?.isAvailable == true }
            .map { file -> file.id }
        val missingFileIds = audioFiles
            .filterNot { file -> availableFileIds.contains(file.id) }
            .map { file -> file.id }

        when {
            availableFileIds.size == audioFiles.size -> {
                if (store.restoreReady(bookId, availableFileIds)) {
                    DeletedBookRecoveryResult.RestoredReady
                } else {
                    DeletedBookRecoveryResult.MissingBook
                }
            }
            availableFileIds.isEmpty() -> {
                val reason = audioFiles
                    .asSequence().firstNotNullOfOrNull { file -> availabilityByFileId[file.id] }
                    .recoveryReason()
                DeletedBookRecoveryResult.AllFilesUnavailable(reason)
            }
            else -> {
                DeletedBookRecoveryResult.PartialFilesUnavailable(
                    availableFileIds = availableFileIds,
                    missingFileIds = missingFileIds
                )
            }
        }
    }

    /**
     * Commits the user-approved split file status.
     * Rechecks that at least one audio row is still marked available by the pending decision before writing PARTIAL state.
     */
    suspend fun confirmPartialRestore(
        bookId: String,
        availableFileIds: List<String>,
        missingFileIds: List<String>
    ): DeletedBookRecoveryResult = withContext(Dispatchers.IO) {
        if (availableFileIds.isEmpty()) {
            return@withContext DeletedBookRecoveryResult.AllFilesUnavailable(AudiobookSchema.AvailabilityStatus.NOT_FOUND.name)
        }
        if (store.restorePartial(bookId, availableFileIds, missingFileIds)) {
            DeletedBookRecoveryResult.RestoredPartial
        } else {
            DeletedBookRecoveryResult.MissingBook
        }
    }
}

/**
 * Converts low-level availability facts into stable dialog detail text.
 * Prefers provider messages, then error codes, then status codes so UI callers never inspect infrastructure exceptions.
 */
private fun AvailabilityResult?.recoveryReason(): String =
    this?.message?.takeIf { it.isNotBlank() }
        ?: this?.errorCode?.takeIf { it.isNotBlank() }
        ?: this?.status?.name?.takeIf { it.isNotBlank() }
        ?: AudiobookSchema.AvailabilityStatus.UNKNOWN.name
