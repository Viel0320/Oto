package com.viel.oto.library.vfs.sourceProvider.webdav

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.oto.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavSourceProviderErrorTest {

    @Test
    fun `openInputStream should map timeout to NETWORK_TIMEOUT`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val error = assertThrows(WebDavException::class.java) {
                    runBlocking {
                        provider.openInputStream(fileNode(rootFor(server)))
                    }
                }

                assertEquals(AudiobookSchema.AvailabilityStatus.TIMEOUT, error.availabilityStatus)
            }
        }
    }

    @Test
    fun `readRange should return null on HTTP 416 out of bounds`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(416))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val result = provider.readRange(fileNode(rootFor(server)), 9999L, 100)

                assertNull(result)
            }
        }
    }

    @Test
    fun `readRange should return null when server returns HTTP 200 instead of 206`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("full file content"))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val result = provider.readRange(fileNode(rootFor(server)), 10L, 5)

                assertNull(result)
            }
        }
    }

    @Test
    fun `listChildren should reject oversized PROPFIND response`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                val largeXml = "<d:multistatus xmlns:d=\"DAV:\">${"x".repeat(9_000_000)}</d:multistatus>"
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setBody(largeXml)
                )
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val error = assertThrows(WebDavException::class.java) {
                    runBlocking {
                        provider.listChildren(directoryNode(rootFor(server)))
                    }
                }

                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, error.availabilityStatus)
            }
        }
    }

    @Test
    fun `exists should return false on 404 not found`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val result = provider.exists(fileNode(rootFor(server)))

                assertEquals(false, result)
            }
        }
    }

    @Test
    fun `rootDirectory should return null on 404`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val result = provider.rootDirectory(rootFor(server))

                assertNull(result)
            }
        }
    }

    @Test
    fun `openInputStream with offset should reject 206 without Content-Range`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(206).setBody("data"))
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())

                val error = assertThrows(WebDavException::class.java) {
                    runBlocking {
                        provider.openInputStream(fileNode(rootFor(server)), offset = 100L)
                    }
                }

                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, error.availabilityStatus)
            }
        }
    }

    private suspend fun withCleartextAllowed(block: suspend () -> Unit) {
        block()
    }

    private fun rootFor(server: MockWebServer) = LibraryRootEntity(
        id = "webdav-root-1",
        sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
        sourceUri = server.url("/library").toString().trimEnd('/'),
        basePath = "",
        displayName = "Remote Library"
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
