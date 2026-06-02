package com.viel.aplayer.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.MIN_SUPPORTED_ABS_SERVER_VERSION
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.net.compareAbsServerVersion
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.ensureSupportedAbsServerVersion
import com.viel.aplayer.abs.sync.AbsConnectionTester
import com.viel.aplayer.data.db.AudiobookSchema
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
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory

class AbsApiClientTest {

    @Test
    fun `source kind must not fallback to saf for unknown value`() {
        assertEquals(LibrarySourceKind.ABS, LibrarySourceKind.from(AudiobookSchema.LibrarySourceType.ABS))
        assertNull(LibrarySourceKind.from("UNKNOWN_SOURCE"))
    }

    @Test
    fun `server version comparator should reject lower malformed and missing versions`() {
        // 详尽的中文注释：连接阶段的版本门槛依赖这里的比较器，因此需要把边界全锁住，
        // 防止未来有人把 `2.35.0`、空版本号或非数字版本号错误放行。
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
        val tempDir = createTempDirectory(prefix = "abs-credential-store").toFile()
        val store = AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )

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
    fun `authorize must use post api authorize with bearer token`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"id":"u1","username":"demo"}}""")
            )
            val client = RealAbsApiClient()

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
    fun `login must use json body`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"id":"u1","username":"demo","token":"t1"}}""")
            )
            val client = RealAbsApiClient()

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
            val client = RealAbsApiClient()
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
            val tester = AbsConnectionTester(RealAbsApiClient())

            try {
                runBlocking {
                    tester.testConnection(server.url("/audiobookshelf/").toString(), "token-456")
                }
                error("Expected unsupported version rejection")
            } catch (error: Exception) {
                // 详尽的中文注释：低版本服务器必须在 `/status` 阶段就被拒绝，
                // 后续 `/api/authorize` 和 `/api/libraries` 都不应该继续发起请求。
                assertTrue(error.message?.contains("版本过低") == true)
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
                    // 记录真正执行 HTTP 的线程，锁住“调用线程与网络线程分离”的回归要求。
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
}
