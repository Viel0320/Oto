package com.viel.aplayer.library.vfs.sourceProvider.webdav

import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.library.vfs.VfsPath
import com.viel.aplayer.library.vfs.VirtualFileSystem
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavSourceProviderPropfindTest {

    @Test
    fun `listChildren should skip failed response status before replacing directory cache`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setHeader("Content-Type", "application/xml")
                        .setBody(
                            multistatus(
                                responseStatus = "HTTP/1.1 404 Not Found",
                                childHref = "/library/missing.mp3",
                                propstatStatus = "HTTP/1.1 200 OK",
                                contentLength = "42"
                            )
                        )
                )
                val cache = RecordingDirectoryListingCache()
                val vfs = VirtualFileSystem(
                    providerResolver = { WebDavSourceProvider(RuntimeEnvironment.getApplication()) },
                    directoryListingCache = cache
                )

                val children = vfs.listChildren(rootDirectoryNode(rootFor(server)))

                assertEquals(emptyList<String>(), children.map { node -> node.metadata.sourcePath })
                assertEquals(emptyList<String>(), cache.replacedChildren.map { metadata -> metadata.sourcePath })
                assertEquals("PROPFIND", server.takeRequest().method)
            }
        }
    }

    @Test
    fun `listChildren should use successful propstat properties when failed propstat appears first`() = runBlocking {
        withCleartextAllowed {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(207)
                        .setHeader("Content-Type", "application/xml")
                        .setBody(mixedPropstatMultistatus())
                )
                val provider = WebDavSourceProvider(RuntimeEnvironment.getApplication())

                val children = provider.listChildren(rootDirectoryNode(rootFor(server)).sourceNode)

                assertEquals(listOf("book.mp3"), children.map { node -> node.metadata.sourcePath })
                assertEquals(128L, children.single().metadata.fileSize)
                assertEquals("audio/mpeg", children.single().metadata.mimeType)
                assertEquals("PROPFIND", server.takeRequest().method)
            }
        }
    }

    private suspend fun withCleartextAllowed(block: suspend () -> Unit) {
        val repository = testAppSettingsRepository("webdav-propfind")
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

    private fun rootFor(server: MockWebServer): LibraryRootEntity =
        LibraryRootEntity(
            id = "webdav-root-1",
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = server.url("/library").toString().trimEnd('/'),
            basePath = "",
            displayName = "Remote Library"
        )

    private fun rootDirectoryNode(root: LibraryRootEntity): VfsNode {
        val metadata = SourceFileMetadata(
            sourcePath = "",
            identity = root.id,
            parentSourcePath = "",
            parentIdentity = root.id,
            displayName = root.displayName,
            isDirectory = true,
            fileSize = 0L,
            lastModified = 0L
        )
        return VfsNode(
            root = root,
            path = VfsPath(""),
            metadata = metadata,
            sourceNode = SourceNode(root = root, metadata = metadata)
        )
    }

    private fun multistatus(
        responseStatus: String? = null,
        childHref: String,
        propstatStatus: String,
        contentLength: String
    ): String {
        val responseStatusXml = responseStatus?.let { "<d:status>$it</d:status>" }.orEmpty()
        return """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>$childHref</d:href>
                $responseStatusXml
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>$contentLength</d:getcontentlength>
                    <d:getcontenttype>audio/mpeg</d:getcontenttype>
                  </d:prop>
                  <d:status>$propstatStatus</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }

    private fun mixedPropstatMultistatus(): String =
        """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/library/book.mp3</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>999</d:getcontentlength>
                    <d:getcontenttype>application/error</d:getcontenttype>
                  </d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>128</d:getcontentlength>
                    <d:getcontenttype>audio/mpeg</d:getcontenttype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

    private class RecordingDirectoryListingCache : DirectoryListingCache {
        var replacedChildren: List<SourceFileMetadata> = emptyList()

        override suspend fun getChildren(directory: VfsNode): List<SourceFileMetadata>? = null

        override suspend fun replaceChildren(directory: VfsNode, children: List<SourceFileMetadata>) {
            replacedChildren = children
        }

        override suspend fun evictRoot(rootId: String) = Unit
    }
}
