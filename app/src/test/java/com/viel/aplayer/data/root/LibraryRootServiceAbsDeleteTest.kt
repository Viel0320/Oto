package com.viel.aplayer.data.root

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncEntity
import com.viel.aplayer.abs.playback.AbsPlaybackSessionEntity
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.library.scan.ScanOutcome
import com.viel.aplayer.library.scan.ScanOutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Locks failure ordering for remote root cleanup.
 * Exercises the real Room database and ABS credential DataStore so root deletion cannot regress into
 * deleting external secrets before the SQLite root deletion has committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryRootServiceAbsDeleteTest {

    @Test
    fun `abs root deletion should remove room-owned rows and credential after commit`() = runBlocking {
        val database = inMemoryDatabase()
        val credentialStore = testCredentialStore("abs-root-delete-success")
        val credential = credentialStore.save(
            baseUrl = "https://abs.example.test",
            token = "token-success",
            credentialId = CREDENTIAL_ID
        )
        val root = absRoot(credential.id)
        val service = serviceFor(database = database, credentialStore = credentialStore)

        try {
            database.seedAbsRootGraph(root)

            service.deleteLibraryRootDataOnly(root)

            assertNull(database.libraryRootDao().getRootById(ROOT_ID))
            assertNull(database.bookDao().getBookById(BOOK_ID))
            assertNull(database.absSyncStateDao().getByRootId(ROOT_ID))
            assertTrue(database.absItemMirrorDao().getByRootId(ROOT_ID).isEmpty())
            assertNull(database.absPlaybackSessionDao().getByBookId(BOOK_ID))
            assertNull(database.absPendingProgressSyncDao().getByBookId(BOOK_ID))
            assertNull(credentialStore.get(CREDENTIAL_ID))
        } finally {
            service.close()
            database.close()
            credentialStore.delete(CREDENTIAL_ID)
        }
    }

    @Test
    fun `abs credential and room rows should survive when root delete transaction fails`() = runBlocking {
        val database = inMemoryDatabase()
        val credentialStore = testCredentialStore("abs-root-delete-failure")
        val credential = credentialStore.save(
            baseUrl = "https://abs.example.test",
            token = "token-failure",
            credentialId = CREDENTIAL_ID
        )
        val root = absRoot(credential.id)
        val service = serviceFor(database = database, credentialStore = credentialStore)

        try {
            database.seedAbsRootGraph(root)
            database.installRootDeleteFailureTrigger()

            val failure = runCatching {
                service.deleteLibraryRootDataOnly(root)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertNotNull(database.libraryRootDao().getRootById(ROOT_ID))
            assertNotNull(database.bookDao().getBookById(BOOK_ID))
            assertNotNull(database.absSyncStateDao().getByRootId(ROOT_ID))
            assertEquals(1, database.absItemMirrorDao().getByRootId(ROOT_ID).size)
            assertNotNull(database.absPlaybackSessionDao().getByBookId(BOOK_ID))
            assertNotNull(database.absPendingProgressSyncDao().getByBookId(BOOK_ID))
            assertNotNull(credentialStore.get(CREDENTIAL_ID))
        } finally {
            service.close()
            database.close()
            credentialStore.delete(CREDENTIAL_ID)
        }
    }

    @Test
    fun `updateAbsLibraryRoot should clear fingerprint when library basePath changes`() = runBlocking {
        val database = inMemoryDatabase()
        val credentialStore = testCredentialStore("abs-root-update")
        val credential = credentialStore.save(
            baseUrl = "https://abs.example.test",
            token = "token-success",
            credentialId = CREDENTIAL_ID
        )
        val root = absRoot(credential.id)
        val service = serviceFor(database = database, credentialStore = credentialStore)

        try {
            database.seedAbsRootGraph(root)

            database.absSyncStateDao().insertOrReplace(
                AbsSyncStateEntity(
                    rootId = ROOT_ID,
                    serverKey = "server-key",
                    libraryId = "library-id",
                    fullListFingerprint = "fingerprint-value"
                )
            )

            service.updateAbsLibraryRoot(
                id = ROOT_ID,
                credentialId = CREDENTIAL_ID,
                libraryId = "library-id",
                displayName = "Updated Display Name"
            )
            assertEquals("fingerprint-value", database.absSyncStateDao().getByRootId(ROOT_ID)?.fullListFingerprint)

            /**
             * Asserts that updating a root's library identifier wipes the cached full list hash.
             * Checks fingerprint preservation when libraryId is unchanged, and nullification on change.
             */
            service.updateAbsLibraryRoot(
                id = ROOT_ID,
                credentialId = CREDENTIAL_ID,
                libraryId = "new-library-id",
                displayName = "Updated Display Name"
            )
            assertNull(database.absSyncStateDao().getByRootId(ROOT_ID))
            assertNull(database.bookDao().getBookById(BOOK_ID))
            assertTrue(database.absItemMirrorDao().getByRootId(ROOT_ID).isEmpty())
        } finally {
            service.close()
            database.close()
            credentialStore.delete(CREDENTIAL_ID)
        }
    }

    private fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

    private fun testCredentialStore(testName: String): AbsCredentialStore {
        val tempDir = createTempDirectory(testName).toFile()
        return AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
    }

    private fun serviceFor(
        database: AppDatabase,
        credentialStore: AbsCredentialStore
    ): LibraryRootGatewayImpl {
        val context = RuntimeEnvironment.getApplication()
        /**
         * Wires memory database back into the store.
         * Instantiates LibraryRootStore using mock database DAO and credentials, and registers it as rootStoreOverride.
         */
        val rootStore = com.viel.aplayer.library.LibraryRootStore(
            context = context,
            rootDaoOverride = database.libraryRootDao(),
            absCredentialStoreOverride = credentialStore
        )
        return LibraryRootGatewayImpl(
            context = context,
            libraryRootDao = database.libraryRootDao(),
            bookDao = database.bookDao(),
            scanScheduler = NoOpScanScheduler,
            cacheEvictionCoordinator = CacheEvictionCoordinator(
                context = context,
                bookDao = database.bookDao(),
                directoryCacheDao = database.directoryCacheDao(),
                directoryChildCacheDao = database.directoryChildCacheDao()
            ),
            rootStoreOverride = rootStore,
            absCredentialStoreOverride = credentialStore,
            databaseOverride = database
        )
    }

    private suspend fun AppDatabase.seedAbsRootGraph(root: LibraryRootEntity) {
        libraryRootDao().insertRoot(root)
        bookDao().insertBook(
            BookEntity(
                id = BOOK_ID,
                rootId = ROOT_ID,
                sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
                title = "ABS Book"
            )
        )
        absSyncStateDao().insertOrReplace(
            AbsSyncStateEntity(
                rootId = ROOT_ID,
                serverKey = "server-key",
                libraryId = "library-id"
            )
        )
        absItemMirrorDao().insertOrReplaceAll(
            listOf(
                AbsItemMirrorEntity(
                    localBookId = BOOK_ID,
                    rootId = ROOT_ID,
                    serverKey = "server-key",
                    remoteItemId = "remote-item-id",
                    state = AudiobookSchema.AbsMirrorState.ACTIVE
                )
            )
        )
        absPlaybackSessionDao().insertOrReplace(
            AbsPlaybackSessionEntity(
                bookId = BOOK_ID,
                remoteItemId = "remote-item-id",
                sessionId = "session-id",
                currentTimeSec = 12.0,
                timeListenedSec = 12.0,
                openedAt = 1_000L,
                state = AudiobookSchema.AbsPlaybackSessionState.OPEN
            )
        )
        absPendingProgressSyncDao().insertOrReplace(
            AbsPendingProgressSyncEntity(
                bookId = BOOK_ID,
                remoteItemId = "remote-item-id",
                currentTimeSec = 12.0,
                timeListenedSec = 12.0,
                durationSec = 120.0,
                updatedAt = 2_000L
            )
        )
    }

    private fun AppDatabase.installRootDeleteFailureTrigger() {
        openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_abs_root_delete
            BEFORE DELETE ON library_roots
            WHEN OLD.id = '$ROOT_ID'
            BEGIN
                SELECT RAISE(ABORT, 'root delete failed');
            END
            """.trimIndent()
        )
    }

    private fun absRoot(credentialId: String): LibraryRootEntity =
        LibraryRootEntity(
            id = ROOT_ID,
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://abs.example.test",
            basePath = "library-id",
            credentialId = credentialId,
            displayName = "ABS Library"
        )

    private object NoOpScanScheduler : ScanScheduler {
        override suspend fun syncLibrary(trigger: String): ScanOutcome =
            ScanOutcome(kind = ScanOutcomeKind.SUCCESS, feedback = null)

        override fun scheduleLibrarySync(trigger: String, requiresNetwork: Boolean) = Unit
    }

    private companion object {
        private const val ROOT_ID = "abs-root-delete-root"
        private const val BOOK_ID = "abs-root-delete-book"
        private const val CREDENTIAL_ID = "abs-root-delete-credential"
    }
}
