package com.viel.aplayer.library.vfs.sourceProvider.webdav

import com.viel.aplayer.data.AppSettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WebDavConnectionTesterTest {

    @Test
    fun `testConnection should succeed with valid credentials`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(207))
                val tester = WebDavConnectionTester(appSettings())

                val result = tester.testConnection(
                    url = server.url("/dav").toString(),
                    username = "user",
                    password = "pass",
                    basePath = ""
                )

                assertEquals(server.url("/").toString().trimEnd('/'), result.endpoint)
                assertEquals("/dav", result.basePath)
                val request = server.takeRequest()
                assertEquals("PROPFIND", request.method)
                assertTrue(request.getHeader("Authorization")?.startsWith("Basic ") == true)
            }
        }
    }

    @Test
    fun `testConnection should throw on 401 unauthorized`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(401))
                val tester = WebDavConnectionTester(appSettings())

                val error = assertThrows(WebDavConnectionTestException::class.java) {
                    runBlocking {
                        tester.testConnection(server.url("/").toString(), "", "", "")
                    }
                }

                assertEquals(WebDavConnectionTestFailureReason.Unauthorized, error.reason)
                assertEquals(401, error.httpCode)
            }
        }
    }

    @Test
    fun `testConnection should throw on 403 forbidden`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(403))
                val tester = WebDavConnectionTester(appSettings())

                val error = assertThrows(WebDavConnectionTestException::class.java) {
                    runBlocking {
                        tester.testConnection(server.url("/").toString(), "", "", "")
                    }
                }

                assertEquals(WebDavConnectionTestFailureReason.Forbidden, error.reason)
            }
        }
    }

    @Test
    fun `testConnection should throw on 404 not found`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404))
                val tester = WebDavConnectionTester(appSettings())

                val error = assertThrows(WebDavConnectionTestException::class.java) {
                    runBlocking {
                        tester.testConnection(server.url("/").toString(), "", "", "")
                    }
                }

                assertEquals(WebDavConnectionTestFailureReason.NotFound, error.reason)
            }
        }
    }

    @Test
    fun `testConnection should reject URL with userinfo`() {
        val tester = WebDavConnectionTester(appSettings())

        val error = assertThrows(WebDavEndpointValidationException::class.java) {
            runBlocking {
                tester.testConnection("http://user:pass@example.com/", "", "", "")
            }
        }

        assertEquals(WebDavEndpointValidationReason.UserInfoNotAllowed, error.reason)
    }

    @Test
    fun `testConnection should reject URL without scheme`() {
        val tester = WebDavConnectionTester(appSettings())

        val error = assertThrows(WebDavEndpointValidationException::class.java) {
            runBlocking {
                tester.testConnection("example.com/dav", "", "", "")
            }
        }

        assertEquals(WebDavEndpointValidationReason.MissingScheme, error.reason)
    }

    @Test
    fun `testConnection should normalize basePath from URL path`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200))
                val tester = WebDavConnectionTester(appSettings())

                val result = tester.testConnection(
                    url = server.url("/remote/library").toString(),
                    username = "",
                    password = "",
                    basePath = ""
                )

                assertEquals("/remote/library", result.basePath)
                assertEquals(server.url("/remote/library").toString(), result.targetUrl)
            }
        }
    }

    private suspend fun withCleartextAllowed(block: suspend () -> Unit) {
        val repository = appSettings()
        repository.updateCleartextTrafficAllowed(true)
        repository.awaitCleartextSetting(true)
        try {
            block()
        } finally {
            repository.updateCleartextTrafficAllowed(false)
            repository.awaitCleartextSetting(false)
        }
    }

    private suspend fun AppSettingsRepository.awaitCleartextSetting(enabled: Boolean) {
        withTimeout(2_000L) {
            while (cachedSettings.isCleartextTrafficAllowed != enabled) {
                delay(10L)
            }
        }
    }

    private fun appSettings() = AppSettingsRepository.getInstance(RuntimeEnvironment.getApplication())
}
