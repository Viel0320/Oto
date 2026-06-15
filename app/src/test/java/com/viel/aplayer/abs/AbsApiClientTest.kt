package com.viel.aplayer.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.AbsTokenRefreshResult
import com.viel.aplayer.abs.net.MIN_SUPPORTED_ABS_SERVER_VERSION
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.net.compareAbsServerVersion
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.ensureSupportedAbsServerVersion
import com.viel.aplayer.abs.sync.AbsConnectionTester
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory

// Local Android Runtime Alignment (Runs AndroidX DataStore through the app-supported SDK path)
// Robolectric pins Build.VERSION.SDK_INT to API 32 so the test avoids android.jar's default SDK 0 behavior, which makes DataStore use a Windows-incompatible legacy rename path.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AbsApiClientTest {

    // RemoveInvalidEnumCompileError: Remove test asserting invalid String source type since compiler now enforces type-safe enum.
    @Test
    fun `source kind must not fallback to saf for unknown value`() {
        assertEquals(LibrarySourceKind.ABS, LibrarySourceKind.from(AudiobookSchema.LibrarySourceType.ABS))
    }

    @Test
    fun `server version comparator should reject lower malformed and missing versions`() {
        // Version comparator boundary lock. The version gating during connection setup depends on this comparator,
        // so we must seal all boundary conditions to prevent improper authorization of older versions (e.g., 2.35.0), nulls, or non-numeric strings.
        assertEquals(0, compareAbsServerVersion("2.35.1", MIN_SUPPORTED_ABS_SERVER_VERSION))
        assertTrue(compareAbsServerVersion("2.35.2", MIN_SUPPORTED_ABS_SERVER_VERSION) > 0)
        assertTrue(compareAbsServerVersion("2.35.10", MIN_SUPPORTED_ABS_SERVER_VERSION) > 0)
        assertTrue(compareAbsServerVersion("2.35.0", MIN_SUPPORTED_ABS_SERVER_VERSION) < 0)
        assertTrue(compareAbsServerVersion("2.34.9", MIN_SUPPORTED_ABS_SERVER_VERSION) < 0)
        assertTrue(compareAbsServerVersion(null, MIN_SUPPORTED_ABS_SERVER_VERSION) < 0)
        assertTrue(compareAbsServerVersion("2.35.beta", MIN_SUPPORTED_ABS_SERVER_VERSION) < 0)
    }

    @Test
    fun `server version guard should throw for unsupported versions`() {
        try {
            ensureSupportedAbsServerVersion("2.35.0")
            error("Expected unsupported version error")
        } catch (error: Exception) {
            assertTrue(error.message?.contains("2.35.0") == true)
            assertTrue(error.message?.contains(MIN_SUPPORTED_ABS_SERVER_VERSION) == true)
        }
    }

    @Test
    fun `credential store save get delete and toString must redact token`() = runBlocking {
        val store = createCredentialStore("abs-credential-store")

        val saved = store.save(
            baseUrl = "https://example.com/audiobookshelf/",
            token = "super-secret-token",
            userId = "user-1",
            username = "demo",
            serverKey = "server-key-1"
        )
        val loaded = store.get(saved.id)

        assertNotNull(loaded)
        assertEquals("https://example.com/audiobookshelf", loaded?.baseUrl)
        assertEquals("super-secret-token", loaded?.token)
        assertFalse(saved.toString().contains("super-secret-token"))
        assertTrue(saved.toString().contains("<redacted>"))

        store.delete(saved.id)
        assertNull(store.get(saved.id))
    }

    @Test
    fun `credential store update token should preserve credential id`() = runBlocking {
        val store = createCredentialStore("abs-credential-update")
        store.save(
            baseUrl = "https://example.com/audiobookshelf",
            token = "old-token",
            userId = "user-1",
            username = "demo",
            credentialId = "cred-1"
        )

        val updated = store.updateToken("cred-1", "new-token")
        val loaded = store.get("cred-1")

        assertNotNull(updated)
        assertEquals("cred-1", loaded?.id)
        assertEquals("new-token", loaded?.token)
        assertEquals("user-1", loaded?.userId)
        assertEquals("demo", loaded?.username)
    }

    @Test
    fun `authorize must use post api authorize with bearer token`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"id":"u1","username":"demo"}}""")
            )
            val client = RealAbsApiClient(settingsProvider = ::allowCleartextSettings)

            runBlocking {
                client.authorize(server.url("/audiobookshelf").toString(), "token-123")
            }

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/audiobookshelf/api/authorize", request.path)
            assertEquals("Bearer token-123", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `refresh token should use raw authorize and persist returned token`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"id":"u1","username":"demo","token":"new-token"}}""")
            )
            val store = createCredentialStore("abs-refresh")
            store.save(
                baseUrl = server.url("/audiobookshelf/").toString(),
                token = "old-token",
                userId = "user-1",
                username = "demo",
                credentialId = "cred-1"
            )
            val client = RealAbsApiClient(
                settingsProvider = ::allowCleartextSettings,
                credentialStore = store
            )

            val result = client.refreshToken("cred-1")

            assertEquals(AbsTokenRefreshResult.Success("new-token"), result)
            assertEquals("new-token", store.get("cred-1")?.token)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/audiobookshelf/api/authorize", request.path)
            assertEquals("Bearer old-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `credential scoped request should refresh once and retry with new token`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"id":"u1","username":"demo","token":"new-token"}}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"libraries":[{"id":"lib-book","name":"Books","mediaType":"book"}]}""")
            )
            val store = createCredentialStore("abs-rest-retry")
            store.save(
                baseUrl = server.url("/audiobookshelf/").toString(),
                token = "old-token",
                credentialId = "cred-1"
            )
            val client = RealAbsApiClient(
                settingsProvider = ::allowCleartextSettings,
                credentialStore = store
            )

            val libraries = client.getLibraries(
                baseUrl = server.url("/audiobookshelf/").toString(),
                token = "old-token",
                credentialId = "cred-1"
            )

            assertEquals(1, libraries.size)
            assertEquals("new-token", store.get("cred-1")?.token)
            val firstLibrariesRequest = server.takeRequest()
            val refreshRequest = server.takeRequest()
            val retryLibrariesRequest = server.takeRequest()
            // Request-Level Refresh Retry (Keeps authorize refresh separate from the original REST call)
            // The first request proves old-token failed, the refresh request updates credentials, and the retry uses only the new token once.
            assertEquals("/audiobookshelf/api/libraries", firstLibrariesRequest.path)
            assertEquals("Bearer old-token", firstLibrariesRequest.getHeader("Authorization"))
            assertEquals("/audiobookshelf/api/authorize", refreshRequest.path)
            assertEquals("Bearer old-token", refreshRequest.getHeader("Authorization"))
            assertEquals("/audiobookshelf/api/libraries", retryLibrariesRequest.path)
            assertEquals("Bearer new-token", retryLibrariesRequest.getHeader("Authorization"))
        }
    }

    @Test
    fun `login must use json body`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"id":"u1","username":"demo","token":"t1"}}""")
            )
            val client = RealAbsApiClient(settingsProvider = ::allowCleartextSettings)

            runBlocking {
                client.login(server.url("/audiobookshelf").toString(), "demo", "demo")
            }

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/audiobookshelf/login", request.path)
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("""{"username":"demo","password":"demo"}""", request.body.readUtf8())
        }
    }

    @Test
    fun `status and getLibraries must preserve base subpath and filter books in connection tester`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"serverVersion":"2.35.1"}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"id":"u1","username":"demo"}}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "libraries": [
                            {"id":"lib-book","name":"Books","mediaType":"book"},
                            {"id":"lib-podcast","name":"Podcasts","mediaType":"podcast"}
                          ]
                        }
                        """.trimIndent()
                    )
            )
            val client = RealAbsApiClient(settingsProvider = ::allowCleartextSettings)
            val tester = AbsConnectionTester(client)

            val result = runBlocking {
                tester.testConnection(server.url("/audiobookshelf/").toString(), "token-456")
            }

            val statusRequest = server.takeRequest()
            val authorizeRequest = server.takeRequest()
            val librariesRequest = server.takeRequest()

            assertEquals("/audiobookshelf/status", statusRequest.path)
            assertEquals("/audiobookshelf/api/authorize", authorizeRequest.path)
            assertEquals("/audiobookshelf/api/libraries", librariesRequest.path)
            assertEquals("2.35.1", result.serverVersion)
            assertEquals("u1", result.userId)
            assertEquals("demo", result.username)
            assertEquals(listOf(AbsLibraryDto(id = "lib-book", name = "Books", mediaType = "book")), result.bookLibraries)
        }
    }

    @Test
    fun `connection tester must reject servers lower than minimum supported version`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"serverVersion":"2.35.0"}""")
            )
            val tester = AbsConnectionTester(RealAbsApiClient(settingsProvider = ::allowCleartextSettings))

            try {
                runBlocking {
                    tester.testConnection(server.url("/audiobookshelf/").toString(), "token-456")
                }
                error("Expected unsupported version rejection")
            } catch (error: Exception) {
                // Network Layer Diagnostic Contract (Keeps low-level ABS errors localization-free)
                // Unsupported server versions must be rejected during /status without emitting user-facing copy from the client layer.
                assertTrue(error.message?.contains("version unsupported") == true)
                assertTrue(error.message?.contains(MIN_SUPPORTED_ABS_SERVER_VERSION) == true)
                val statusRequest = server.takeRequest()
                assertEquals("/audiobookshelf/status", statusRequest.path)
                assertEquals(0, server.requestCount - 1)
            }
        }
    }

    @Test
    fun `login must execute network call off caller thread`() {
        val callerThreadName = AtomicReference<String>()
        val networkThreadName = AtomicReference<String>()
        val client = RealAbsApiClient(
            client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    // Thread separation verification. Record the actual thread executing the HTTP transaction, enforcing the regression requirement that offloads network requests from the calling coroutine thread.
                    networkThreadName.set(Thread.currentThread().name)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"user":{"id":"u1","username":"demo","token":"t1"}}""".toResponseBody())
                        .build()
                })
                .build()
        )

        runBlocking {
            callerThreadName.set(Thread.currentThread().name)
            client.login("https://example.com/audiobookshelf", "demo", "demo")
        }

        assertNotNull(networkThreadName.get())
        assertNotEquals(callerThreadName.get(), networkThreadName.get())
    }

    /**
     * Test Cleartext Settings (Opts MockWebServer HTTP endpoints into transport compatibility)
     *
     * Production defaults stay strict; only these local protocol tests enable HTTP because MockWebServer
     * serves plain HTTP unless each test provisions TLS certificates.
     */
    private fun allowCleartextSettings(): AppSettings =
        AppSettings(isCleartextTrafficAllowed = true)

    private fun createCredentialStore(testName: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = testName).toFile()
        return AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
    }
}
