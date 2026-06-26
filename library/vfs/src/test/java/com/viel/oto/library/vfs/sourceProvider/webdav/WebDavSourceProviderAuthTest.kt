package com.viel.oto.library.vfs.sourceProvider.webdav

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.webdav.WebDavCredentialStore
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.oto.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.runBlocking
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
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavSourceProviderAuthTest {

    @Test
    fun `openInputStream should include auth header when credentials exist`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("content"))
                val credentialStore = testCredentialStore("webdav-auth-header")
                val provider = testWebDavSourceProvider(
                    context = RuntimeEnvironment.getApplication(),
                    webDavCredentialStore = credentialStore
                )
                val root = rootFor(server, credentialId = "cred-1")
                credentialStore.save("testuser", "testpass", "cred-1")

                provider.openInputStream(fileNode(root))?.close()

                val request = server.takeRequest()
                val auth = request.getHeader("Authorization")
                assertTrue(auth?.startsWith("Basic ") == true)
            }
        }
    }

    @Test
    fun `readRange should map 401 to AUTH_REQUIRED status`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(401))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val error = assertThrows(WebDavException::class.java) {
                    runBlocking {
                        provider.readRange(fileNode(rootFor(server)), 0L, 100)
                    }
                }

                assertEquals(AudiobookSchema.AvailabilityStatus.AUTH_FAILED, error.availabilityStatus)
            }
        }
    }

    @Test
    fun `listChildren should map 403 to ACCESS_DENIED status`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(403))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val error = assertThrows(WebDavException::class.java) {
                    runBlocking {
                        provider.listChildren(directoryNode(rootFor(server)))
                    }
                }

                assertEquals(AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED, error.availabilityStatus)
            }
        }
    }

    private suspend fun withCleartextAllowed(block: suspend () -> Unit) {
        block()
    }

    /**
     * Creates an isolated credential DataStore for auth-header assertions.
     */
    private fun testCredentialStore(testName: String): WebDavCredentialStore {
        val tempDir = createTempDirectory(testName).toFile()
        return WebDavCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "webdav_credentials.preferences_pb") }
            )
        )
    }

    private fun rootFor(server: MockWebServer, credentialId: String? = null) =
        LibraryRootEntity(
            id = "webdav-root-1",
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = server.url("/library").toString().trimEnd('/'),
            basePath = "",
            displayName = "Remote Library",
            credentialId = credentialId
        )

    private fun fileNode(root: LibraryRootEntity) = SourceNode(
        root = root,
        metadata = SourceFileMetadata(
            sourcePath = "audio.mp3",
            identity = "audio.mp3",
            parentSourcePath = "",
            parentIdentity = root.id,
            displayName = "audio.mp3",
            isDirectory = false,
            fileSize = 1000L,
            lastModified = 0L
        )
    )

    private fun directoryNode(root: LibraryRootEntity) = SourceNode(
        root = root,
        metadata = SourceFileMetadata(
            sourcePath = "",
            identity = root.id,
            parentSourcePath = "",
            parentIdentity = root.id,
            displayName = root.displayName,
            isDirectory = true,
            fileSize = 0L,
            lastModified = 0L
        )
    )
}
