package com.viel.oto.application.library.recovery

import com.viel.oto.data.abs.sync.AbsItemMirrorDao
import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.application.usecase.DeletedBookRecoveryStore
import com.viel.oto.application.usecase.DeletedBookRecoveryUseCase
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.DeletedBookRecoveryProjection
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.availability.AvailabilityChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wires Room storage and provider availability into the recovery scene.
 * Keeps recovery reads and commands separate from SettingsRootCommands, LibraryFacade, and the home catalog module.
 */
class DefaultDeletedBookRecoveryModule(
    bookDao: BookDao,
    libraryRootDao: LibraryRootDao,
    absItemMirrorDao: AbsItemMirrorDao,
    availabilityChecker: AvailabilityChecker
) : DeletedBookRecoveryReadModel, DeletedBookRecoveryCommands {
    private val useCase = DeletedBookRecoveryUseCase(
        store = RoomDeletedBookRecoveryStore(
            bookDao = bookDao,
            libraryRootDao = libraryRootDao,
            absItemMirrorDao = absItemMirrorDao
        ),
        checkRootAvailability = availabilityChecker::checkRoot,
        checkAudioFilesAvailability = availabilityChecker::checkBookFiles
    )

    override fun observeRecoverableBooks(): Flow<List<DeletedBookRecoveryItem>> =
        useCase.observeRecoverableBooks()

    override suspend fun restoreBook(bookId: String): DeletedBookRecoveryResult =
        useCase.restoreBook(bookId)

    override suspend fun confirmPartialRestore(
        bookId: String,
        availableFileIds: List<String>,
        missingFileIds: List<String>
    ): DeletedBookRecoveryResult =
        useCase.confirmPartialRestore(bookId, availableFileIds, missingFileIds)
}

/**
 * Adapts Room DAOs to the narrow recovery persistence seam.
 * Restricts restore writes to dedicated BookDao transactions and reads ABS mirror state locally without starting remote sync work.
 */
private class RoomDeletedBookRecoveryStore(
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao,
    private val absItemMirrorDao: AbsItemMirrorDao
) : DeletedBookRecoveryStore {
    override fun observeRecoverableBooks(): Flow<List<DeletedBookRecoveryItem>> =
        bookDao.observeDeletedBookRecoveryProjections()
            .map { projections -> projections.map { projection -> projection.toRecoveryItem() } }

    override suspend fun getBook(bookId: String): BookEntity? =
        bookDao.getBookById(bookId)

    override suspend fun getRoot(rootId: String): LibraryRootEntity? =
        libraryRootDao.getRootById(rootId)

    override suspend fun getAudioFiles(bookId: String): List<BookFileEntity> =
        bookDao.getFilesForBookList(bookId)

    override suspend fun getAbsMirror(bookId: String): AbsItemMirrorEntity? =
        absItemMirrorDao.getByLocalBookId(bookId)

    override suspend fun restoreReady(bookId: String, readyFileIds: List<String>): Boolean =
        bookDao.restoreDeletedBookReady(bookId, readyFileIds)

    override suspend fun restorePartial(bookId: String, readyFileIds: List<String>, missingFileIds: List<String>): Boolean =
        bookDao.restoreDeletedBookPartial(bookId, readyFileIds, missingFileIds)
}

/**
 * Converts DAO rows to the application recovery item.
 * Keeps DAO projection columns and Compose-facing field names aligned without adding Room annotations to the UI model.
 */
private fun DeletedBookRecoveryProjection.toRecoveryItem(): DeletedBookRecoveryItem =
    DeletedBookRecoveryItem(
        bookId = bookId,
        title = title,
        author = author,
        narrator = narrator,
        durationMs = durationMs,
        coverPath = coverPath,
        coverLastUpdated = coverLastUpdated,
        progressPercent = progressPercent,
        sourceLabel = sourceLabel
    )
