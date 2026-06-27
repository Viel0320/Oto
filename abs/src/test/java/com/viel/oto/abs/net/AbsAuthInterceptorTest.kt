package com.viel.oto.abs.net

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.abs.auth.AbsCredentialStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Drives the interceptor through a real OkHttp call against MockWebServer.
 * The recorded request lets the test assert exactly which Authorization header reached the wire.
 */
class AbsAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `missing auth tag proceeds without authorization header`() {
        val received = execute(interceptor = AbsAuthInterceptor(), tag = null)
        assertNull(received.getHeader("Authorization"))
    }

    @Test
    fun `credential id resolves token through store`() {
        val store = createCredentialStore(credentialId = "cred-1", token = "token-from-store")
        val received = execute(AbsAuthInterceptor(store), AbsAuth(credentialId = "cred-1"))
        assertEquals("Bearer token-from-store", received.getHeader("Authorization"))
    }

    @Test
    fun `credential id present but store null falls back to inline token`() {
        // The credentialId.let block runs, but a null store makes the inner let return null,
        // so the elvis operator falls through to auth.token.
        val received = execute(
            interceptor = AbsAuthInterceptor(credentialStore = null),
            tag = AbsAuth(token = "inline", credentialId = "cred-1")
        )
        assertEquals("Bearer inline", received.getHeader("Authorization"))
    }

    @Test
    fun `unknown credential id with no inline token sends no header`() {
        // store.get returns null -> resolved token is null -> isNullOrBlank short-circuits to plain proceed.
        val store = createCredentialStore(credentialId = "cred-1", token = "token-1")
        val received = execute(AbsAuthInterceptor(store), AbsAuth(credentialId = "absent"))
        assertNull(received.getHeader("Authorization"))
    }

    @Test
    fun `inline token without credential id injects bearer header`() {
        val received = execute(AbsAuthInterceptor(), AbsAuth(token = "raw-token"))
        assertEquals("Bearer raw-token", received.getHeader("Authorization"))
    }

    @Test
    fun `blank token does not add authorization header`() {
        val received = execute(AbsAuthInterceptor(), AbsAuth(token = "   "))
        assertNull(received.getHeader("Authorization"))
    }

    @Test
    fun `store token preferred over inline token when credential resolves`() {
        val store = createCredentialStore(credentialId = "cred-1", token = "store-token")
        val received = execute(AbsAuthInterceptor(store), AbsAuth(token = "inline-token", credentialId = "cred-1"))
        assertEquals("Bearer store-token", received.getHeader("Authorization"))
    }

    private fun execute(interceptor: AbsAuthInterceptor, tag: AbsAuth?): okhttp3.mockwebserver.RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val builder = Request.Builder().url(server.url("/api/x")).get()
        if (tag != null) {
            builder.tag(AbsAuth::class.java, tag)
        }
        client.newCall(builder.build()).execute().use { response ->
            response.body.string()
        }
        return server.takeRequest()
    }

    private fun createCredentialStore(credentialId: String, token: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-auth-interceptor").toFile()
        val store = AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
        runBlocking {
            store.save(
                baseUrl = "https://example.com",
                token = token,
                credentialId = credentialId
            )
        }
        return store
    }
}
