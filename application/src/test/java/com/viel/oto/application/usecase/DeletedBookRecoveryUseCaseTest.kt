package com.viel.oto.application.usecase

import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.application.library.recovery.DeletedBookRecoveryItem
import com.viel.oto.application.library.recovery.DeletedBookRecoveryResult
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.availability.AvailabilityResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks soft-delete restore rules.
 * Uses fake stores and fake availability probes so recovery logic is verified without SAF, WebDAV, or ABS network access.
 */
class DeletedBookRecoveryUseCaseTest {

    @Test
    fun deletedBookWithAvailableRootAndFilesRestoresReady() = runBlocking {
        val store = fakeStore()
        val useCase = useCaseFor(store)

        val result = useCase.restoreBook(BOOK_ID)

        assertEquals(DeletedBookRecoveryResult.RestoredReady, result)
        assertEquals(AudiobookSchema.BookStatus.READY, store.books[BOOK_ID]?.status)
        assertEquals(
            mapOf(FILE_ONE_ID to AudiobookSchema.FileStatus.READY, FILE_TWO_ID to AudiobookSchema.FileStatus.READY),
            store.fileStatuses()
        )
        assertEquals(listOf("restoreReady:$BOOK_ID:${FILE_ONE_ID},${FILE_TWO_ID}"), store.events)
    }

    @Test
    fun missingRootReturnsFailureWithoutWrites() = runBlocking {
        val store = fakeStore(roots = emptyMap())
        val useCase = useCaseFor(store)

        val result = useCase.restoreBook(BOOK_ID)

        assertEquals(DeletedBookRecoveryResult.MissingRoot, result)
        assertEquals(emptyList<String>(), store.events)
    }

    @Test
    fun unavailableRootReturnsReasonWithoutWrites() = runBlocking {
        val store = fakeStore()
        val useCase = useCaseFor(
            store = store,
            rootAvailability = AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.REVOKED,
                errorCode = "REVOKED"
            )
        )

        val result = useCase.restoreBook(BOOK_ID)

        assertEquals(DeletedBookRecoveryResult.RootUnavailable("REVOKED"), result)
        assertEquals(emptyList<String>(), store.events)
    }

    @Test
    fun allUnavailableFilesReturnFailureWithoutWrites() = runBlocking {
        val store = fakeStore()
        val useCase = useCaseFor(
            store = store,
            fileAvailability = mapOf(
                FILE_ONE_ID to unavailableFile("missing-one"),
                FILE_TWO_ID to unavailableFile("missing-two")
            )
        )

        val result = useCase.restoreBook(BOOK_ID)

        assertEquals(DeletedBookRecoveryResult.AllFilesUnavailable("missing-one"), result)
        assertEquals(emptyList<String>(), store.events)
    }

    @Test
    fun partialAvailabilityWaitsForConfirmationThenRestoresPartial() = runBlocking {
        val store = fakeStore()
        val useCase = useCaseFor(
            store = store,
            fileAvailability = mapOf(
                FILE_ONE_ID to availableResult(),
                FILE_TWO_ID to unavailableFile("missing-two")
            )
        )

        val preflight = useCase.restoreBook(BOOK_ID)

        assertEquals(
            DeletedBookRecoveryResult.PartialFilesUnavailable(
                availableFileIds = listOf(FILE_ONE_ID),
                missingFileIds = listOf(FILE_TWO_ID)
            ),
            preflight
        )
        assertEquals(emptyList<String>(), store.events)

        val confirmed = useCase.confirmPartialRestore(
            bookId = BOOK_ID,
            availableFileIds = listOf(FILE_ONE_ID),
            missingFileIds = listOf(FILE_TWO_ID)
        )

        assertEquals(DeletedBookRecoveryResult.RestoredPartial, confirmed)
        assertEquals(AudiobookSchema.BookStatus.PARTIAL, store.books[BOOK_ID]?.status)
        assertEquals(
            mapOf(FILE_ONE_ID to AudiobookSchema.FileStatus.READY, FILE_TWO_ID to AudiobookSchema.FileStatus.MISSING),
            store.fileStatuses()
        )
    }

    @Test
    fun activeAbsMirrorAllowsLocalRestore() = runBlocking {
        val store = fakeStore(
            mirrors = mapOf(BOOK_ID to mirror(AudiobookSchema.AbsMirrorState.ACTIVE))
        )
        val useCase = useCaseFor(store)

        val result = useCase.restoreBook(BOOK_ID)

        assertEquals(DeletedBookRecoveryResult.RestoredReady, result)
    }

    @Test
    fun remoteDeletedAbsMirrorBlocksManualRestore() = runBlocking {
        val store = fakeStore(
            mirrors = mapOf(BOOK_ID to mirror(AudiobookSchema.AbsMirrorState.REMOTE_DELETED))
        )
        val useCase = useCaseFor(store)

        val result = useCase.restoreBook(BOOK_ID)

        assertEquals(DeletedBookRecoveryResult.AbsRemoteDeleted, result)
        assertEquals(emptyList<String>(), store.events)
    }

    @Test
    fun nonDeletedBookIsTreatedAsMissingAndDoesNotWrite() = runBlocking {
        val store = fakeStore(
            books = mapOf(BOOK_ID to book(status = AudiobookSchema.BookStatus.READY))
        )
        val useCase = useCaseFor(store)

        val result = useCase.restoreBook(BOOK_ID)

        assertEquals(DeletedBookRecoveryResult.MissingBook, result)
        assertEquals(emptyList<String>(), store.events)
    }

    @Test
    fun emptyAvailableIdsCannotConfirmPartialRestore() = runBlocking {
        val store = fakeStore()
        val useCase = useCaseFor(store)

        val result = useCase.confirmPartialRestore(
            bookId = BOOK_ID,
            availableFileIds = emptyList(),
            missingFileIds = listOf(FILE_ONE_ID, FILE_TWO_ID)
        )

        assertTrue(result is DeletedBookRecoveryResult.AllFilesUnavailable)
        assertEquals(emptyList<String>(), store.events)
    }

    private fun useCaseFor(
        store: FakeDeletedBookRecoveryStore,
        rootAvailability: AvailabilityResult = availableResult(),
        fileAvailability: Map<String, AvailabilityResult> = mapOf(
            FILE_ONE_ID to availableResult(),
            FILE_TWO_ID to availableResult()
        )
    ): DeletedBookRecoveryUseCase =
        DeletedBookRecoveryUseCase(
            store = store,
            checkRootAvailability = { root ->
                require(root.id == ROOT_ID)
                rootAvailability
            },
            checkAudioFilesAvailability = { files ->
                files.associate { file -> file.id to (fileAvailability[file.id] ?: unavailableFile("missing")) }
            }
        )

    private class FakeDeletedBookRecoveryStore(
        val books: MutableMap<String, BookEntity>,
        private val roots: Map<String, LibraryRootEntity>,
        private val filesByBookId: MutableMap<String, List<BookFileEntity>>,
        private val mirrors: Map<String, AbsItemMirrorEntity>
    ) : DeletedBookRecoveryStore {
        val events = mutableListOf<String>()

        override fun observeRecoverableBooks(): Flow<List<DeletedBookRecoveryItem>> =
            flowOf(emptyList())

        override suspend fun getBook(bookId: String): BookEntity? =
            books[bookId]

        override suspend fun getRoot(rootId: String): LibraryRootEntity? =
            roots[rootId]

        override suspend fun getAudioFiles(bookId: String): List<BookFileEntity> =
            filesByBookId[bookId].orEmpty()

        override suspend fun getAbsMirror(bookId: String): AbsItemMirrorEntity? =
            mirrors[bookId]

        override suspend fun restoreReady(bookId: String, readyFileIds: List<String>): Boolean {
            events += "restoreReady:$bookId:${readyFileIds.joinToString(",")}"
            val book = books[bookId].takeIf { it?.status == AudiobookSchema.BookStatus.DELETED } ?: return false
            books[bookId] = book.copy(status = AudiobookSchema.BookStatus.READY)
            filesByBookId[bookId] = filesByBookId[bookId].orEmpty().map { file ->
                if (file.id in readyFileIds) file.copy(status = AudiobookSchema.FileStatus.READY) else file
            }
            return true
        }

        override suspend fun restorePartial(
            bookId: String,
            readyFileIds: List<String>,
            missingFileIds: List<String>
        ): Boolean {
            events += "restorePartial:$bookId:${readyFileIds.joinToString(",")}:${missingFileIds.joinToString(",")}"
            val book = books[bookId].takeIf { it?.status == AudiobookSchema.BookStatus.DELETED } ?: return false
            books[bookId] = book.copy(status = AudiobookSchema.BookStatus.PARTIAL)
            filesByBookId[bookId] = filesByBookId[bookId].orEmpty().map { file ->
                when (file.id) {
                    in readyFileIds -> file.copy(status = AudiobookSchema.FileStatus.READY)
                    in missingFileIds -> file.copy(status = AudiobookSchema.FileStatus.MISSING)
                    else -> file
                }
            }
            return true
        }

        fun fileStatuses(): Map<String, AudiobookSchema.FileStatus> =
            filesByBookId[BOOK_ID].orEmpty().associate { file -> file.id to file.status }
    }

    private companion object {
        private const val BOOK_ID = "book-id"
        private const val ROOT_ID = "root-id"
        private const val FILE_ONE_ID = "file-one"
        private const val FILE_TWO_ID = "file-two"

        private fun fakeStore(
            books: Map<String, BookEntity> = mapOf(BOOK_ID to book()),
            roots: Map<String, LibraryRootEntity> = mapOf(ROOT_ID to root()),
            filesByBookId: Map<String, List<BookFileEntity>> = mapOf(
                BOOK_ID to listOf(audioFile(FILE_ONE_ID, 0), audioFile(FILE_TWO_ID, 1))
            ),
            mirrors: Map<String, AbsItemMirrorEntity> = emptyMap()
        ): FakeDeletedBookRecoveryStore =
            FakeDeletedBookRecoveryStore(
                books = books.toMutableMap(),
                roots = roots,
                filesByBookId = filesByBookId.toMutableMap(),
                mirrors = mirrors
            )

        private fun book(status: AudiobookSchema.BookStatus = AudiobookSchema.BookStatus.DELETED): BookEntity =
            BookEntity(
                id = BOOK_ID,
                rootId = ROOT_ID,
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = "Deleted Book",
                status = status
            )

        private fun root(): LibraryRootEntity =
            LibraryRootEntity(
                id = ROOT_ID,
                sourceUri = "content://root",
                displayName = "Local Root"
            )

        private fun audioFile(id: String, index: Int): BookFileEntity =
            BookFileEntity(
                id = id,
                bookId = BOOK_ID,
                rootId = ROOT_ID,
                index = index,
                sourcePath = "track-$index.mp3",
                sourceIdentity = "track-$index",
                displayName = "Track $index",
                durationMs = 1_000L,
                fileSize = 10L,
                lastModified = 0L
            )

        private fun mirror(state: AudiobookSchema.AbsMirrorState): AbsItemMirrorEntity =
            AbsItemMirrorEntity(
                localBookId = BOOK_ID,
                rootId = ROOT_ID,
                serverKey = "server",
                remoteItemId = "remote-item",
                state = state
            )

        private fun availableResult(): AvailabilityResult =
            AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)

        private fun unavailableFile(reason: String): AvailabilityResult =
            AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                errorCode = reason
            )
    }
}
