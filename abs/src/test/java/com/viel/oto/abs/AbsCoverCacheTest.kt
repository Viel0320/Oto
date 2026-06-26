package com.viel.oto.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.sync.AbsCoverCache
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.shared.policy.UnsafeNetworkPolicyViolation
import com.viel.oto.shared.model.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AbsCoverCacheTest {

    @Test
    fun `http cover download should be blocked before bearer token request is sent`() = runBlocking {
        MockWebServer().use { server ->
            val credentialStore = createCredentialStore("http://example.com/AudiobookShelf/", "token-1")
            val cache = createCoverCache(
                credentialStore = credentialStore,
                settingsProvider = { AppSettings() }
            )

            try {
                cache.downloadCover(rootFor(server), "item-1")
                fail("Expected cleartext ABS cover download to be blocked")
            } catch (error: UnsafeNetworkPolicyViolation) {
                assertEquals("ABS cover download", error.operation)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test
    fun `cover download should preserve base path encode item id and attach bearer when cleartext is allowed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody("not-a-real-image-but-a-real-stream")
            )
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val cache = createCoverCache(
                credentialStore = credentialStore,
                settingsProvider = { AppSettings(isCleartextTrafficAllowed = true) }
            )

            val result = cache.downloadCover(rootFor(server), "item/with space")
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/AudiobookShelf/api/items/item%2Fwith%20space/cover", request.path)
            assertEquals("Bearer token-1", request.getHeader("Authorization"))
            assertNotNull(result.originalPath)
        }
    }

    private fun createCoverCache(
        credentialStore: AbsCredentialStore,
        settingsProvider: () -> AppSettings
    ): AbsCoverCache =
        AbsCoverCache(
            context = RuntimeEnvironment.getApplication(),
            credentialStore = credentialStore,
            settingsProvider = settingsProvider
        )

    private fun rootFor(server: MockWebServer): LibraryRootEntity =
        LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )

    private fun createCredentialStore(baseUrl: String, token: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-cover-cache").toFile()
        val store = AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
        runBlocking {
            store.save(
                baseUrl = baseUrl,
                token = token,
                userId = "user-1",
                username = "demo-user",
                credentialId = "cred-1"
            )
        }
        return store
    }
}
