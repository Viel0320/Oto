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
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())
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
    fun `openInputStream should reject partial content without content range`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setBody("56789")
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())
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
        val repository = testAppSettingsRepository("webdav-range")
        repository.updateCleartextTrafficAllowed(true)
        repository.awaitCleartextSetting(enabled = true)
        try {
            block()
        } finally {
            repository.updateCleartextTrafficAllowed(false)
            repository.awaitCleartextSetting(enabled = false)
        }
    }

    private suspend fun AppSettingsRepository.awaitCleartextSetting(enabled: Boolean) {
        withTimeout(2_000L) {
            while (cachedSettings.isCleartextTrafficAllowed != enabled) {
                delay(10L)
            }
        }
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
