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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavSourceProviderXmlTest {

    @Test
    fun `listChildren should parse file size and mime type from PROPFIND`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
                            <d:multistatus xmlns:d="DAV:">
                              <d:response>
                                <d:href>/library/book.mp3</d:href>
                                <d:propstat>
                                  <d:prop>
                                    <d:getcontentlength>2048576</d:getcontentlength>
                                    <d:getcontenttype>audio/mpeg</d:getcontenttype>
                                    <d:getetag>"abc123"</d:getetag>
                                  </d:prop>
                                  <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                              </d:response>
                            </d:multistatus>
                            """.trimIndent()
                        )
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val children = provider.listChildren(directoryNode(rootFor(server)))

                assertEquals(1, children.size)
                assertEquals("book.mp3", children[0].metadata.sourcePath)
                assertEquals(2048576L, children[0].metadata.fileSize)
                assertEquals("audio/mpeg", children[0].metadata.mimeType)
                assertEquals("abc123", children[0].metadata.etag)
            }
        }
    }

    @Test
    fun `listChildren should detect directory from trailing slash in href`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
                            <d:multistatus xmlns:d="DAV:">
                              <d:response>
                                <d:href>/library/folder/</d:href>
                                <d:propstat>
                                  <d:prop>
                                    <d:getcontentlength>0</d:getcontentlength>
                                  </d:prop>
                                  <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                              </d:response>
                            </d:multistatus>
                            """.trimIndent()
                        )
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val children = provider.listChildren(directoryNode(rootFor(server)))

                assertEquals(1, children.size)
                assertTrue(children[0].metadata.isDirectory)
                assertEquals("folder", children[0].metadata.sourcePath)
            }
        }
    }

    @Test
    fun `listChildren should detect directory from DAV collection property`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
                            <d:multistatus xmlns:d="DAV:">
                              <d:response>
                                <d:href>/library/dir</d:href>
                                <d:propstat>
                                  <d:prop>
                                    <d:resourcetype><d:collection/></d:resourcetype>
                                  </d:prop>
                                  <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                              </d:response>
                            </d:multistatus>
                            """.trimIndent()
                        )
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val children = provider.listChildren(directoryNode(rootFor(server)))

                assertEquals(1, children.size)
                assertTrue(children[0].metadata.isDirectory)
            }
        }
    }

    @Test
    fun `listChildren should reject malformed XML`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setBody("<malformed><xml")
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

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
    fun `listChildren should filter out parent directory from Depth 1 response`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
                            <d:multistatus xmlns:d="DAV:">
                              <d:response>
                                <d:href>/library/</d:href>
                                <d:propstat>
                                  <d:prop>
                                    <d:resourcetype><d:collection/></d:resourcetype>
                                  </d:prop>
                                  <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                              </d:response>
                              <d:response>
                                <d:href>/library/child.mp3</d:href>
                                <d:propstat>
                                  <d:prop>
                                    <d:getcontentlength>100</d:getcontentlength>
                                  </d:prop>
                                  <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                              </d:response>
                            </d:multistatus>
                            """.trimIndent()
                        )
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val children = provider.listChildren(directoryNode(rootFor(server)))

                assertEquals(1, children.size)
                assertEquals("child.mp3", children[0].metadata.sourcePath)
            }
        }
    }

    @Test
    fun `resolve should return single node from Depth 0 PROPFIND`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
                            <d:multistatus xmlns:d="DAV:">
                              <d:response>
                                <d:href>/library/target.mp3</d:href>
                                <d:propstat>
                                  <d:prop>
                                    <d:getcontentlength>512</d:getcontentlength>
                                  </d:prop>
                                  <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                              </d:response>
                            </d:multistatus>
                            """.trimIndent()
                        )
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val node = provider.resolve(rootFor(server), "target.mp3")

                assertEquals("target.mp3", node?.metadata?.sourcePath)
                assertEquals(512L, node?.metadata?.fileSize)
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
