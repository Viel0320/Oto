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

// Local WebDAV Runtime Alignment (Exercise the real Context-backed provider through JVM tests)
// WebDavSourceProvider owns SharedPreferences credentials and AppSettings-backed network policy, so Robolectric keeps range validation on the production request path.
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

                // Mismatched Range Regression (Fail fast when the server serves a different byte window)
                // Metadata parsers and range cache entries trust exact offsets, so a 206 response for bytes 0-3 must not satisfy a bytes 5-8 request.
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

                // Missing Range Header Regression (Require protocol proof before returning an offset stream)
                // A 206 response without Content-Range cannot prove the stream begins at byte 5, so seek playback must reject it instead of trusting body bytes.
                assertTrue(error is WebDavException)
                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, (error as WebDavException).availabilityStatus)
                assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            }
        }
    }

    private suspend fun withCleartextAllowed(block: suspend () -> Unit) {
        // Mock Server Transport Policy (Temporarily enable local HTTP for WebDAV protocol tests)
        // Production defaults still block cleartext; the test flips the cached setting only around MockWebServer requests and restores it afterward.
        val repository = AppSettingsRepository.getInstance(RuntimeEnvironment.getApplication())
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
        // Cleartext Test Gate Synchronization (Wait until the provider-visible cached settings match the test transport)
        // WebDAV requests read cachedSettings synchronously, so this loop prevents races between DataStore writes and MockWebServer HTTP dispatch.
        withTimeout(2_000L) {
            while (cachedSettings.isCleartextTrafficAllowed != enabled) {
                delay(10L)
            }
        }
    }

    private fun sourceNodeFor(server: MockWebServer): SourceNode {
        // Direct Source Node Fixture (Bypass PROPFIND so tests isolate GET Range behavior)
        // The provider only needs root URL and file metadata for stream and range reads, keeping the regression focused on Content-Range validation.
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
