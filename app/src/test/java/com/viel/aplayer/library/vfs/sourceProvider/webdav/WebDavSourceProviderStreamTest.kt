package com.viel.aplayer.library.vfs.sourceProvider.webdav

import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavSourceProviderStreamTest {

    @Test
    fun `openInputStream should read full file content`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("file content"))
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val stream = provider.openInputStream(fileNode(rootFor(server)))
                val content = stream?.readBytes()

                assertArrayEquals("file content".toByteArray(), content)
                stream?.close()
            }
        }
    }

    @Test
    fun `openInputStream with offset should send Range header`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 10-19/100")
                        .setBody("0123456789")
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val stream = provider.openInputStream(fileNode(rootFor(server)), offset = 10L)
                val content = stream?.readBytes()

                assertArrayEquals("0123456789".toByteArray(), content)
                stream?.close()
                val request = server.takeRequest()
                assertEquals("bytes=10-", request.getHeader("Range"))
            }
        }
    }

    @Test
    fun `openInputStream should fallback skip when server ignores Range header`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789abcdef"))
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val stream = provider.openInputStream(fileNode(rootFor(server)), offset = 10L)
                val content = stream?.readBytes()

                assertArrayEquals("abcdef".toByteArray(), content)
                stream?.close()
            }
        }
    }

    @Test
    fun `openInputStream should throw on out-of-bounds offset with 416 response`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(416))
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val error = assertThrows(WebDavException::class.java) {
                    runBlocking {
                        provider.openInputStream(fileNode(rootFor(server)), offset = 9999L)
                    }
                }

                assertEquals(AudiobookSchema.AvailabilityStatus.NOT_FOUND, error.availabilityStatus)
            }
        }
    }

    @Test
    fun `readRange should read exact byte window`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 5-9/100")
                        .setBody("56789")
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val data = provider.readRange(fileNode(rootFor(server)), offset = 5L, length = 5)

                assertArrayEquals("56789".toByteArray(), data)
                val request = server.takeRequest()
                assertEquals("bytes=5-9", request.getHeader("Range"))
            }
        }
    }

    @Test
    fun `readRange should clamp end offset to file size`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 95-99/100")
                        .setBody("95-99")
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())
                val node = fileNode(rootFor(server), fileSize = 100L)

                val data = provider.readRange(node, offset = 95L, length = 1000)

                assertArrayEquals("95-99".toByteArray(), data)
                val request = server.takeRequest()
                assertEquals("bytes=95-99", request.getHeader("Range"))
            }
        }
    }

    private suspend fun withCleartextAllowed(block: suspend () -> Unit) {
        val repository = AppSettingsRepository.getInstance(RuntimeEnvironment.getApplication())
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

    private fun rootFor(server: MockWebServer) = LibraryRootEntity(
        id = "webdav-root-1",
        sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
        sourceUri = server.url("/library").toString().trimEnd('/'),
        basePath = "",
        displayName = "Remote Library"
    )

    private fun fileNode(root: LibraryRootEntity, fileSize: Long = 1000L) = SourceNode(
        root = root,
        metadata = SourceFileMetadata(
            sourcePath = "audio.mp3",
            identity = "audio.mp3",
            parentSourcePath = "",
            parentIdentity = root.id,
            displayName = "audio.mp3",
            isDirectory = false,
            fileSize = fileSize,
            lastModified = 0L
        )
    )
}
