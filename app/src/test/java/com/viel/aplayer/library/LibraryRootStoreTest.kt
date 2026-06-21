package com.viel.aplayer.library

import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
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

/**
 * Locks source-root persistence boundaries.
 * Exercises root registration and edit flows at the store layer so credential staging is verified before UI, gateway, or scan scheduling code can hide ordering failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryRootStoreTest {

    @Test
    fun `webdav endpoint normalization should reject userinfo credentials`() = runBlocking {
        val credentialStore = WebDavCredentialStore(RuntimeEnvironment.getApplication())
        val rootDao = RecordingLibraryRootDao(initialRoots = emptyList())
        val store = LibraryRootStore(
            context = RuntimeEnvironment.getApplication(),
            rootDaoOverride = rootDao,
            webDavCredentialStoreOverride = credentialStore
        )

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
        val credentialStore = WebDavCredentialStore(RuntimeEnvironment.getApplication())
        val rootDao = FailingInsertLibraryRootDao(initialRoots = emptyList())
        val store = LibraryRootStore(
            context = RuntimeEnvironment.getApplication(),
            rootDaoOverride = rootDao,
            webDavCredentialStoreOverride = credentialStore
        )

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
        val credentialStore = WebDavCredentialStore(RuntimeEnvironment.getApplication())
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
        val store = LibraryRootStore(
            context = RuntimeEnvironment.getApplication(),
            rootDaoOverride = rootDao,
            webDavCredentialStoreOverride = credentialStore
        )

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
        val credentialStore = WebDavCredentialStore(RuntimeEnvironment.getApplication())
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
        val store = LibraryRootStore(
            context = RuntimeEnvironment.getApplication(),
            rootDaoOverride = rootDao,
            webDavCredentialStoreOverride = credentialStore
        )

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
        val credentialStore = WebDavCredentialStore(RuntimeEnvironment.getApplication())
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
        val store = LibraryRootStore(
            context = RuntimeEnvironment.getApplication(),
            rootDaoOverride = rootDao,
            webDavCredentialStoreOverride = credentialStore
        )

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
    }
}
