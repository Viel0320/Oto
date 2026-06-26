package com.viel.oto.library.vfs.sourceProvider.webdav

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.oto.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavSourceProviderRangeTest {

    @Test
    fun `readRange should reject mismatched partial content range`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-3/100")
                        .setBody("0123")
                )
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())
                val node = sourceNodeFor(server)

                val error = runCatching {
                    provider.readRange(node, offset = 5L, length = 4)
                }.exceptionOrNull()

                assertTrue(error is WebDavException)
                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, (error as WebDavException).availabilityStatus)
                assertEquals("bytes=5-8", server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test
    fun `readRange should throw typed failure when response body exceeds requested length`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 5-8/100")
                        .setBody("56789")
                )
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())
                val node = sourceNodeFor(server)

                val error = runCatching {
                    provider.readRange(node, offset = 5L, length = 4)
                }.exceptionOrNull()

                assertTrue(error is WebDavRangeBodyTooLargeException)
                val typedError = error as WebDavRangeBodyTooLargeException
                assertEquals(WebDavRangeBodyTooLargeException.CODE, typedError.code)
                assertEquals(4, typedError.requestedLength)
                assertEquals(5L, typedError.observedBytes)
                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, typedError.availabilityStatus)
                assertEquals("bytes=5-8", server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test
    fun `openInputStream should reject partial content without content range`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setBody("56789")
                )
                val provider = testWebDavSourceProvider(RuntimeEnvironment.getApplication())
                val node = sourceNodeFor(server)

                val error = runCatching {
                    provider.openInputStream(node, offset = 5L)?.close()
                }.exceptionOrNull()

                assertTrue(error is WebDavException)
                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, (error as WebDavException).availabilityStatus)
                assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            }
        }
    }

    private suspend fun withCleartextAllowed(block: suspend () -> Unit) {
        block()
    }

    private fun sourceNodeFor(server: MockWebServer): SourceNode {
        val root = LibraryRootEntity(
            id = "webdav-root-1",
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = server.url("/library").toString().trimEnd('/'),
            basePath = "",
            displayName = "Remote Library"
        )
        return SourceNode(
            root = root,
            metadata = SourceFileMetadata(
                sourcePath = "book/audio.mp3",
                identity = "book/audio.mp3",
                parentSourcePath = "book",
                parentIdentity = "book",
                displayName = "audio.mp3",
                isDirectory = false,
                fileSize = 100L,
                lastModified = 0L
            )
        )
    }
}
