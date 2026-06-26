package com.viel.oto.application.usecase

import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.data.abs.sync.AbsSyncStateDao
import com.viel.oto.data.abs.sync.AbsSyncStateEntity
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.webdav.WebDavCredentialStore
import com.viel.oto.library.root.LibraryRootGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Locks the settings root snapshot projection: stream combination, per-root book counting, sync-state
 * association, and the schema-to-presentation enum derivations exposed to the settings UI.
 *
 * Runs under Robolectric only because the credential stores require DataStore/Context construction; the
 * core projection logic is driven entirely through faked interface boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsQueryUseCaseTest {

    @Test
    fun `snapshot projection counts books per root and associates sync state`() = runBlocking {
        val roots = listOf(
            root(
                id = "root-saf",
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                sourceUri = "content://saf/tree",
                basePath = ""
            ),
            root(
                id = "root-abs",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = "https://abs.example.test",
                basePath = "library-1",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE,
                status = AudiobookSchema.LibraryRootStatus.ACTIVE
            )
        )
        val books = listOf(
            book(id = "b1", rootId = "root-saf"),
            book(id = "b2", rootId = "root-saf"),
            book(id = "b3", rootId = "root-abs")
        )
        val syncStates = listOf(
            AbsSyncStateEntity(
                rootId = "root-abs",
                serverKey = "server-1",
                libraryId = "library-1",
                lastFullSyncAt = 4242L,
                lastError = "boom"
            )
        )
        val useCase = useCase(roots = roots, books = books, syncStates = syncStates)

        val snapshots = useCase.observeLibraryRootSnapshots().first()

        assertEquals(2, snapshots.size)
        val saf = snapshots.first { it.rootId == "root-saf" }
        val abs = snapshots.first { it.rootId == "root-abs" }

        assertEquals(2, saf.importedBookCount)
        assertEquals(1, abs.importedBookCount)
        // SAF root has no sync state row, so ABS-specific fields stay null.
        assertNull(saf.absLastError)
        assertNull(saf.absLastFullSyncAt)
        assertEquals("boom", abs.absLastError)
        assertEquals(4242L, abs.absLastFullSyncAt)
    }

    @Test
    fun `root with no books reports zero imported count`() = runBlocking {
        val roots = listOf(
            root(
                id = "empty-root",
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                sourceUri = "content://saf/empty",
                basePath = ""
            )
        )
        val useCase = useCase(roots = roots, books = emptyList(), syncStates = emptyList())

        val snapshot = useCase.observeLibraryRootSnapshots().first().single()

        assertEquals(0, snapshot.importedBookCount)
        assertNull(snapshot.absLastError)
    }

    @Test
    fun `source kind derivation maps each schema source type`() = runBlocking {
        val roots = listOf(
            root("saf", AudiobookSchema.LibrarySourceType.SAF, "content://x", ""),
            root("dav", AudiobookSchema.LibrarySourceType.WEBDAV, "https://dav", "/m"),
            root("abs", AudiobookSchema.LibrarySourceType.ABS, "https://abs", "lib")
        )
        val useCase = useCase(roots = roots, books = emptyList(), syncStates = emptyList())

        val byId = useCase.observeLibraryRootSnapshots().first().associateBy { it.rootId }

        assertEquals(SettingsRootSourceKind.SAF, byId.getValue("saf").sourceKind)
        assertEquals(SettingsRootSourceKind.WEB_DAV, byId.getValue("dav").sourceKind)
        assertEquals(SettingsRootSourceKind.ABS, byId.getValue("abs").sourceKind)
        assertTrue(byId.getValue("abs").isAbsRoot)
        assertTrue(byId.getValue("dav").isWebDavRoot)
        assertFalse(byId.getValue("saf").isAbsRoot)
    }

    @Test
    fun `status and availability kinds derive from schema and gate helper flags`() = runBlocking {
        val roots = listOf(
            root(
                id = "revoked",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = "https://abs",
                basePath = "lib",
                status = AudiobookSchema.LibraryRootStatus.REVOKED,
                availabilityStatus = AudiobookSchema.AvailabilityStatus.AUTH_FAILED
            ),
            root(
                id = "available",
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                sourceUri = "content://x",
                basePath = "",
                status = AudiobookSchema.LibraryRootStatus.ACTIVE,
                availabilityStatus = AudiobookSchema.AvailabilityStatus.AVAILABLE
            ),
            root(
                id = "unknown",
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                sourceUri = "content://y",
                basePath = "",
                status = AudiobookSchema.LibraryRootStatus.ERROR,
                availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN
            )
        )
        val useCase = useCase(roots = roots, books = emptyList(), syncStates = emptyList())

        val byId = useCase.observeLibraryRootSnapshots().first().associateBy { it.rootId }

        val revoked = byId.getValue("revoked")
        assertEquals(SettingsRootStatusKind.REVOKED, revoked.statusKind)
        assertEquals(SettingsRootAvailabilityKind.AUTH_FAILED, revoked.availabilityKind)
        assertFalse(revoked.isActive)
        assertTrue(revoked.hasKnownAvailability)
        assertFalse(revoked.isAvailable)

        val available = byId.getValue("available")
        assertEquals(SettingsRootStatusKind.ACTIVE, available.statusKind)
        assertTrue(available.isActive)
        assertTrue(available.isAvailable)

        val unknown = byId.getValue("unknown")
        assertEquals(SettingsRootStatusKind.ERROR, unknown.statusKind)
        assertEquals(SettingsRootAvailabilityKind.UNKNOWN, unknown.availabilityKind)
        assertFalse(unknown.hasKnownAvailability)
    }

    private fun useCase(
        roots: List<LibraryRootEntity>,
        books: List<BookEntity>,
        syncStates: List<AbsSyncStateEntity>
    ): SettingsQueryUseCase =
        SettingsQueryUseCase(
            libraryRootGateway = fakeRootGateway(roots),
            absSyncStateDao = fakeSyncStateDao(syncStates),
            bookDao = fakeBookDao(books),
            libraryRootDao = fakeRootDao(),
            webDavCredentialStore = WebDavCredentialStore.createForTesting(
                tempDataStore("webdav-settings-query")
            ),
            absCredentialStore = AbsCredentialStore.createForTesting(
                tempDataStore("abs-settings-query")
            )
        )

    private fun tempDataStore(
        name: String
    ): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { java.io.File.createTempFile(name, ".preferences_pb") }
        )

    private fun fakeRootGateway(roots: List<LibraryRootEntity>): LibraryRootGateway =
        Proxy.newProxyInstance(
            LibraryRootGateway::class.java.classLoader,
            arrayOf(LibraryRootGateway::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "observeLibraryRoots" -> flowOf(roots)
                else -> throw UnsupportedOperationException("Unexpected method: ${method.name}")
            }
        } as LibraryRootGateway

    private fun fakeSyncStateDao(syncStates: List<AbsSyncStateEntity>): AbsSyncStateDao =
        Proxy.newProxyInstance(
            AbsSyncStateDao::class.java.classLoader,
            arrayOf(AbsSyncStateDao::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "observeAll" -> flowOf(syncStates)
                else -> throw UnsupportedOperationException("Unexpected method: ${method.name}")
            }
        } as AbsSyncStateDao

    private fun fakeBookDao(books: List<BookEntity>): BookDao =
        Proxy.newProxyInstance(
            BookDao::class.java.classLoader,
            arrayOf(BookDao::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAllBooks" -> flowOf(books)
                else -> throw UnsupportedOperationException("Unexpected method: ${method.name}")
            }
        } as BookDao

    private fun fakeRootDao(): LibraryRootDao =
        Proxy.newProxyInstance(
            LibraryRootDao::class.java.classLoader,
            arrayOf(LibraryRootDao::class.java)
        ) { _, method, _ ->
            throw UnsupportedOperationException("Unexpected method: ${method.name}")
        } as LibraryRootDao

    private fun root(
        id: String,
        sourceType: AudiobookSchema.LibrarySourceType,
        sourceUri: String,
        basePath: String,
        availabilityStatus: AudiobookSchema.AvailabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
        status: AudiobookSchema.LibraryRootStatus = AudiobookSchema.LibraryRootStatus.ACTIVE
    ): LibraryRootEntity =
        LibraryRootEntity(
            id = id,
            sourceType = sourceType,
            sourceUri = sourceUri,
            basePath = basePath,
            availabilityStatus = availabilityStatus,
            displayName = id,
            status = status
        )

    private fun book(id: String, rootId: String): BookEntity =
        BookEntity(
            id = id,
            rootId = rootId,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = id
        )
}
