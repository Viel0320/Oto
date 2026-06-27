package com.viel.oto.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.webdav.WebDavCredentialStore
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * Locks source-root persistence boundaries.
 * Exercises root registration and edit flows at the store layer so credential staging is verified before UI, gateway, or scan scheduling code can hide ordering failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryRootStoreTest {

    @Test
    fun `saf edit target should reuse registered root with same tree identity`() {
        val editingRoot = safRoot(
            id = "editing-root",
            sourceUri = "content://com.android.externalstorage.documents/tree/primary%3AOld"
        )
        val reusableRoot = safRoot(
            id = "reusable-root",
            sourceUri = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
            status = AudiobookSchema.LibraryRootStatus.REVOKED,
            availabilityStatus = AudiobookSchema.AvailabilityStatus.REVOKED,
            availabilityErrorCode = "REVOKED"
        )

        val updated = resolveSafRootEditTarget(
            editingRoot = editingRoot,
            roots = listOf(editingRoot, reusableRoot),
            normalizedUri = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
            displayName = "Audiobooks",
            now = 1234L
        )

        assertEquals("reusable-root", updated.id)
        assertEquals("Audiobooks", updated.displayName)
        assertEquals(1234L, updated.grantedAt)
        assertEquals(AudiobookSchema.LibraryRootStatus.ACTIVE, updated.status)
        assertEquals(AudiobookSchema.AvailabilityStatus.UNKNOWN, updated.availabilityStatus)
        assertEquals(0L, updated.lastAvailabilityCheckedAt)
        assertNull(updated.lastAvailabilityErrorCode)
    }

    @Test
    fun `saf edit target should update edited root when no reusable tree exists`() {
        val editingRoot = safRoot(
            id = "editing-root",
            sourceUri = "content://com.android.externalstorage.documents/tree/primary%3AOld",
            displayName = "Old"
        )

        val updated = resolveSafRootEditTarget(
            editingRoot = editingRoot,
            roots = listOf(editingRoot),
            normalizedUri = "content://com.android.externalstorage.documents/tree/primary%3ANew",
            displayName = "New",
            now = 5678L
        )

        assertEquals("editing-root", updated.id)
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3ANew", updated.sourceUri)
        assertEquals("New", updated.displayName)
        assertEquals(5678L, updated.grantedAt)
        assertEquals(AudiobookSchema.LibraryRootStatus.ACTIVE, updated.status)
    }

    @Test
    fun `webdav endpoint normalization should reject userinfo credentials`() = runBlocking {
        val credentialStore = testCredentialStore("webdav-userinfo")
        val rootDao = RecordingLibraryRootDao(initialRoots = emptyList())
        val store = storeFor("webdav-userinfo", rootDao, credentialStore)

        val failure = runCatching {
            store.addWebDavRoot(
                url = "https://user:pass@example.com/dav",
                username = "stored-user",
                password = "stored-password",
                displayName = "Remote Library",
                basePath = ""
            )
        }.exceptionOrNull()

        assertTrue(failure is WebDavEndpointValidationException)
        assertEquals(WebDavEndpointValidationReason.UserInfoNotAllowed, (failure as WebDavEndpointValidationException).reason)
        assertEquals("WEBDAV_USERINFO_NOT_ALLOWED", failure.message)
        assertTrue(rootDao.getAllRootsOnce().isEmpty())
    }

    @Test
    fun `new webdav add failure should delete staged credential`() = runBlocking {
        val credentialStore = testCredentialStore("webdav-new-failure")
        val rootDao = FailingInsertLibraryRootDao(initialRoots = emptyList())
        val store = storeFor("webdav-new-failure", rootDao, credentialStore)

        try {
            val failure = runCatching {
                store.addWebDavRoot(
                    url = "https://dav-new.example.test/audio",
                    username = "new-user",
                    password = "new-password",
                    displayName = "New Library",
                    basePath = "/audio"
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertNull(credentialStore.get(rootDao.failedRoot?.credentialId))
        } finally {
            credentialStore.delete(rootDao.failedRoot?.credentialId)
        }
    }

    @Test
    fun `webdav update success should bind staged credential and delete previous credential`() = runBlocking {
        val credentialStore = testCredentialStore("webdav-update-success")
        val oldCredential = credentialStore.save(
            username = "old-success-user",
            password = "old-success-password",
            credentialId = "webdav-old-success-credential"
        )
        val existingRoot = webDavRoot(
            id = "webdav-success-root",
            credentialId = oldCredential.id
        )
        val rootDao = RecordingLibraryRootDao(initialRoots = listOf(existingRoot))
        val store = storeFor("webdav-update-success", rootDao, credentialStore)

        try {
            val updated = store.updateWebDavRoot(
                id = existingRoot.id,
                url = "https://dav.example.test/success",
                username = "new-success-user",
                password = "new-success-password",
                displayName = "Success Library",
                basePath = "/success"
            )

            assertNotEquals(oldCredential.id, updated.credentialId)
            assertNull(credentialStore.get(oldCredential.id))
            assertEquals("new-success-user", credentialStore.get(updated.credentialId)?.username)
            assertEquals("new-success-password", credentialStore.get(updated.credentialId)?.password)
            assertEquals(updated, rootDao.getRootById(existingRoot.id))
        } finally {
            credentialStore.delete(oldCredential.id)
            credentialStore.delete(rootDao.getRootById(existingRoot.id)?.credentialId)
        }
    }

    @Test
    fun `webdav update failure should preserve old credential and delete staged credential`() = runBlocking {
        val credentialStore = testCredentialStore("webdav-update-failure")
        val oldCredential = credentialStore.save(
            username = "old-user",
            password = "old-password",
            credentialId = "webdav-old-update-credential"
        )
        val existingRoot = webDavRoot(
            id = "webdav-update-root",
            credentialId = oldCredential.id
        )
        val rootDao = FailingInsertLibraryRootDao(initialRoots = listOf(existingRoot))
        val store = storeFor("webdav-update-failure", rootDao, credentialStore)

        try {
            val failure = runCatching {
                store.updateWebDavRoot(
                    id = existingRoot.id,
                    url = "https://dav.example.test/changed",
                    username = "new-user",
                    password = "new-password",
                    displayName = "Changed Library",
                    basePath = "/changed"
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("old-user", credentialStore.get(oldCredential.id)?.username)
            assertEquals("old-password", credentialStore.get(oldCredential.id)?.password)
            assertNotEquals(oldCredential.id, rootDao.failedRoot?.credentialId)
            assertNull(credentialStore.get(rootDao.failedRoot?.credentialId))
        } finally {
            credentialStore.delete(oldCredential.id)
            credentialStore.delete(rootDao.failedRoot?.credentialId)
        }
    }

    @Test
    fun `deduplicated webdav add failure should preserve old credential and delete staged credential`() = runBlocking {
        val credentialStore = testCredentialStore("webdav-add-dedupe-failure")
        val oldCredential = credentialStore.save(
            username = "existing-user",
            password = "existing-password",
            credentialId = "webdav-old-add-credential"
        )
        val existingRoot = webDavRoot(
            id = "webdav-add-root",
            credentialId = oldCredential.id
        )
        val rootDao = FailingInsertLibraryRootDao(initialRoots = listOf(existingRoot))
        val store = storeFor("webdav-add-dedupe-failure", rootDao, credentialStore)

        try {
            val failure = runCatching {
                store.addWebDavRoot(
                    url = "https://dav.example.test/audio",
                    username = "replacement-user",
                    password = "replacement-password",
                    displayName = "Replacement Library",
                    basePath = "/audio"
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("existing-user", credentialStore.get(oldCredential.id)?.username)
            assertEquals("existing-password", credentialStore.get(oldCredential.id)?.password)
            assertNotEquals(oldCredential.id, rootDao.failedRoot?.credentialId)
            assertNull(credentialStore.get(rootDao.failedRoot?.credentialId))
        } finally {
            credentialStore.delete(oldCredential.id)
            credentialStore.delete(rootDao.failedRoot?.credentialId)
        }
    }

    private class FailingInsertLibraryRootDao(
        initialRoots: List<LibraryRootEntity>
    ) : RecordingLibraryRootDao(initialRoots) {
        var failedRoot: LibraryRootEntity? = null

        override suspend fun insertRoot(root: LibraryRootEntity) {
            failedRoot = root
            throw IllegalStateException("insert-root-failed")
        }
    }

    private open class RecordingLibraryRootDao(
        initialRoots: List<LibraryRootEntity>
    ) : LibraryRootDao {
        private val roots = initialRoots.toMutableList()

        override fun getAllRoots(): Flow<List<LibraryRootEntity>> = flowOf(roots)

        override suspend fun getActiveRootsOnce(): List<LibraryRootEntity> =
            roots.filter { root -> root.status == AudiobookSchema.LibraryRootStatus.ACTIVE }

        override suspend fun getActiveAbsRootsOnce(): List<LibraryRootEntity> =
            roots.filter(::isActiveAbsRoot)

        override suspend fun getRootById(id: String): LibraryRootEntity? =
            roots.firstOrNull { root -> root.id == id }

        override suspend fun getAllRootsOnce(): List<LibraryRootEntity> = roots

        override suspend fun insertRoot(root: LibraryRootEntity) {
            roots.removeAll { existing -> existing.id == root.id }
            roots += root
        }

        override suspend fun updateRootGrantState(id: String, displayName: String, grantedAt: Long, status: AudiobookSchema.LibraryRootStatus) {
            error("Unexpected updateRootGrantState call in LibraryRootStoreTest")
        }

        override suspend fun updateRootScanState(id: String, lastScannedAt: Long, status: AudiobookSchema.LibraryRootStatus) {
            error("Unexpected updateRootScanState call in LibraryRootStoreTest")
        }

        override suspend fun updateRootStatus(id: String, status: AudiobookSchema.LibraryRootStatus) {
            error("Unexpected updateRootStatus call in LibraryRootStoreTest")
        }

        override suspend fun updateRootAvailability(
            id: String,
            availabilityStatus: AudiobookSchema.AvailabilityStatus,
            checkedAt: Long,
            errorCode: String?
        ) {
            error("Unexpected updateRootAvailability call in LibraryRootStoreTest")
        }

        override suspend fun deleteRoot(root: LibraryRootEntity) {
            roots.removeAll { existing -> existing.id == root.id }
        }

        private fun isActiveAbsRoot(root: LibraryRootEntity): Boolean {
            return root.status == AudiobookSchema.LibraryRootStatus.ACTIVE &&
                root.sourceType == AudiobookSchema.LibrarySourceType.ABS
        }
    }

    private companion object {
        fun storeFor(
            testName: String,
            rootDao: LibraryRootDao,
            credentialStore: WebDavCredentialStore
        ): LibraryRootStore =
            LibraryRootStore(
                context = RuntimeEnvironment.getApplication(),
                rootDao = rootDao,
                webDavCredentialStore = credentialStore,
                appSettingsRepository = testSettingsRepository(testName)
            )

        fun testSettingsRepository(testName: String): AppSettingsRepository {
            val tempDir = createTempDirectory(testName).toFile()
            return AppSettingsRepository.createForTesting(
                PreferenceDataStoreFactory.create(
                    produceFile = { File(tempDir, "settings.preferences_pb") }
                )
            )
        }

        /**
         * Creates a per-test WebDAV credential DataStore so staged credentials cannot leak across root-store cases.
         */
        fun testCredentialStore(testName: String): WebDavCredentialStore {
            val tempDir = createTempDirectory("$testName-credentials").toFile()
            return WebDavCredentialStore.createForTesting(
                PreferenceDataStoreFactory.create(
                    produceFile = { File(tempDir, "webdav_credentials.preferences_pb") }
                )
            )
        }

        private fun webDavRoot(id: String, credentialId: String): LibraryRootEntity =
            LibraryRootEntity(
                id = id,
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                sourceUri = "https://dav.example.test",
                basePath = "/audio",
                credentialId = credentialId,
                displayName = "Remote Library",
                status = AudiobookSchema.LibraryRootStatus.ACTIVE
            )

        private fun safRoot(
            id: String,
            sourceUri: String,
            displayName: String = "Local Library",
            status: AudiobookSchema.LibraryRootStatus = AudiobookSchema.LibraryRootStatus.ACTIVE,
            availabilityStatus: AudiobookSchema.AvailabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
            availabilityErrorCode: String? = null
        ): LibraryRootEntity =
            LibraryRootEntity(
                id = id,
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                sourceUri = sourceUri,
                displayName = displayName,
                status = status,
                availabilityStatus = availabilityStatus,
                lastAvailabilityCheckedAt = if (availabilityErrorCode == null) 0L else 42L,
                lastAvailabilityErrorCode = availabilityErrorCode
            )
    }
}
