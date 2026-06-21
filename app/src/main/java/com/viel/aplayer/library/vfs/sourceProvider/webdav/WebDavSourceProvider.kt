package com.viel.aplayer.library.vfs.sourceProvider.webdav

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.RemoteAvailabilityMappingPolicy
import com.viel.aplayer.library.availability.RemoteAvailabilityProtocol
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProvider
import com.viel.aplayer.library.vfs.sourceProvider.SourceCapabilities
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import com.viel.aplayer.network.UnsafeNetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Maps WebDAV HTTP and network failures to standardized availability statuses.
 */
class WebDavException(
    val availabilityStatus: AudiobookSchema.AvailabilityStatus,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Represents one HTTP resource returned by a WebDAV PROPFIND response.
 */
private data class WebDavResource(
    val sourcePath: String,
    val href: String,
    val uri: String,
    val displayName: String,
    val isDirectory: Boolean,
    val fileSize: Long,
    val lastModified: Long,
    val etag: String?,
    val mimeType: String?
)

/**
 * Stores the parsed byte interval returned by a WebDAV partial response.
 *
 * The provider validates only the start and inclusive end offsets because VFS callers already
 * know the requested byte window and do not need total-size coupling here.
 */
private data class ContentRangeWindow(
    val start: Long,
    val end: Long
)

/**
 * OkHttp-powered WebDAV source provider for PROPFIND, GET, and byte-range reads.
 */
class WebDavSourceProvider(context: Context) : LibrarySourceProvider {
    override val kind: LibrarySourceKind = LibrarySourceKind.WEBDAV
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsDirectoryListing = true,
        supportsLastModified = true,
        supportsEtag = true,
        supportsRangeRead = true
    )

    private val appSettingsRepository = AppSettingsRepository.getInstance(context.applicationContext)

    private val clientFactory = WebDavHttpClientFactory(
        connectTimeoutSeconds = 10,
        readTimeoutSeconds = 45,
        callTimeoutSeconds = 60
    )

    private val credentialStore = WebDavCredentialStore(context.applicationContext)

    private fun getClient(): OkHttpClient =
        clientFactory.clientFor(UnsafeNetworkPolicy.isInsecureTlsAllowed(appSettingsRepository.cachedSettings))

    override suspend fun rootDirectory(root: LibraryRootEntity): SourceNode? =
        try {
            propfind(root, sourcePath = "", depth = "0")
                .firstOrNull()
                ?.toSourceNode(root)
                ?: rootFallbackResource(root).toSourceNode(root)
        } catch (error: WebDavException) {
            if (error.availabilityStatus == AudiobookSchema.AvailabilityStatus.NOT_FOUND) null else throw error
        }

    override suspend fun resolve(root: LibraryRootEntity, sourcePath: String): SourceNode? {
        val normalizedPath = normalizeSourcePath(sourcePath)
        return try {
            propfind(root, sourcePath = normalizedPath, depth = "0")
                .firstOrNull { it.sourcePath == normalizedPath }
                ?.toSourceNode(root)
        } catch (error: WebDavException) {
            if (error.availabilityStatus == AudiobookSchema.AvailabilityStatus.NOT_FOUND) null else throw error
        }
    }

    override suspend fun listChildren(directory: SourceNode): List<SourceNode> {
        if (!directory.metadata.isDirectory) return emptyList()
        val parentPath = normalizeSourcePath(directory.metadata.sourcePath)
        return propfind(directory.root, sourcePath = parentPath, depth = "1")
            .asSequence()
            .filterNot { it.sourcePath == parentPath }
            .filter { parentSourcePathOf(it.sourcePath) == parentPath }
            .map { it.toSourceNode(directory.root) }
            .toList()
    }

    override suspend fun openInputStream(file: SourceNode): InputStream? =
        openInputStream(file, offset = 0L)

    override suspend fun openInputStream(file: SourceNode, offset: Long): InputStream? {
        if (file.metadata.isDirectory) return null
        val url = urlFor(file.root, file.metadata.sourcePath, directory = false)
        val request = Request.Builder()
            .url(url)
            .get()
            .applyAuth(file.root)
            .apply {
                if (offset > 0L) header("Range", "bytes=$offset-")
            }
            .build()
        val openStart = com.viel.aplayer.logger.VfsLogger.mark()
        val response = executeRequest(file.root, request)
        if (offset > 0L && response.code == HTTP_RANGE_NOT_SATISFIABLE) {
            response.close()
            throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                message = "WebDAV Range position is out of bounds: ${file.metadata.sourcePath}"
            )
        }
        if (!response.isSuccessful) {
            response.close()
            throw response.toWebDavException("GET")
        }
        if (offset > 0L) {
            try {
                response.requireMatchingContentRange(start = offset, end = null, operation = "GET")
            } catch (error: WebDavException) {
                response.close()
                throw error
            }
        }

        val body = response.body
        val stream = body.byteStream()

        if (offset > 0L && response.code == HTTP_OK) {
            withContext(Dispatchers.IO) { runCatching { skipFully(stream, offset) } }
                .onFailure {
                    stream.close()
                    response.close()
                    throw WebDavException(
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                        message = "WebDAV fallback skip failed: ${file.metadata.sourcePath}",
                        cause = it
                    )
                }
        }

        com.viel.aplayer.logger.VfsLogger.logWebDavOpen(
            sourcePath = file.metadata.sourcePath,
            offset = offset,
            costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(openStart),
            httpCode = response.code,
            success = true
        )

        return ResponseClosingInputStream(stream, response)
    }

    override suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            if (file.metadata.isDirectory || offset < 0L || length <= 0) return@withContext ByteArray(0)
            val rangeEnd = boundedRangeEnd(file, offset, length) ?: return@withContext null
            val rangeStart = com.viel.aplayer.logger.VfsLogger.mark()
            val request = Request.Builder()
                .url(urlFor(file.root, file.metadata.sourcePath, directory = false))
                .get()
                .header("Range", "bytes=$offset-$rangeEnd")
                .applyAuth(file.root)
                .build()
            executeRequestBlocking(file.root, request).use { response ->
                when {
                    response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                        com.viel.aplayer.logger.VfsLogger.logWebDavRange(
                            sourcePath = file.metadata.sourcePath,
                            offset = offset,
                            requestedLength = length,
                            costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(rangeStart),
                            actualBytes = null
                        )
                        return@withContext null
                    }
                    response.code == HTTP_OK -> {
                        com.viel.aplayer.logger.VfsLogger.logWebDavRange(
                            sourcePath = file.metadata.sourcePath,
                            offset = offset,
                            requestedLength = length,
                            costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(rangeStart),
                            actualBytes = null
                        )
                        return@withContext null
                    }
                    !response.isSuccessful -> throw response.toWebDavException("GET Range")
                }
                response.requireMatchingContentRange(start = offset, end = rangeEnd, operation = "GET Range")
                val body = response.body
                val result = body.byteStream().use { stream -> stream.readAtMost(length) }
                com.viel.aplayer.logger.VfsLogger.logWebDavRange(
                    sourcePath = file.metadata.sourcePath,
                    offset = offset,
                    requestedLength = length,
                    costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(rangeStart),
                    actualBytes = result.size
                )
                result
            }
        }

    override suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor? {
        return null
    }

    override suspend fun exists(node: SourceNode): Boolean =
        try {
            resolve(node.root, node.metadata.sourcePath) != null
        } catch (error: WebDavException) {
            if (error.availabilityStatus == AudiobookSchema.AvailabilityStatus.NOT_FOUND) false else throw error
        }

    private suspend fun propfind(root: LibraryRootEntity, sourcePath: String, depth: String): List<WebDavResource> =
        withContext(Dispatchers.IO) {
            val propfindStart = com.viel.aplayer.logger.VfsLogger.mark()
            val url = urlFor(root, sourcePath, directory = true)
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", WebDavProtocol.PROPFIND_ALL_PROPERTIES_BODY)
                .header("Depth", depth)
                .header("Accept", "application/xml,text/xml,*/*")
                .applyAuth(root)
                .build()
            executeRequestBlocking(root, request).use { response ->
                if (response.code == HTTP_NOT_FOUND) {
                    throw response.toWebDavException("PROPFIND")
                }
                if (response.code != HTTP_MULTI_STATUS && !response.isSuccessful) {
                    throw response.toWebDavException("PROPFIND")
                }
                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > MAX_PROPFIND_RESPONSE_SIZE) {
                    throw WebDavException(
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                        message = "WebDAV PROPFIND response content-length is too large: $contentLength bytes (limit: $MAX_PROPFIND_RESPONSE_SIZE)"
                    )
                }

                val xml = try {
                    body.byteStream().use { stream ->
                        stream.readStringWithLimit(MAX_PROPFIND_RESPONSE_SIZE)
                    }
                } catch (e: IOException) {
                    throw WebDavException(
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                        message = "Exceeded WebDAV PROPFIND response secure reading limit of $MAX_PROPFIND_RESPONSE_SIZE bytes",
                        cause = e
                    )
                }

                if (xml.isBlank()) {
                    com.viel.aplayer.logger.VfsLogger.logWebDavPropfind(
                        sourcePath = sourcePath, depth = depth,
                        costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(propfindStart),
                        resourceCount = 1
                    )
                    return@withContext listOf(rootFallbackResource(root))
                }
                val resources = parsePropfindResponse(root, xml)
                com.viel.aplayer.logger.VfsLogger.logWebDavPropfind(
                    sourcePath = sourcePath, depth = depth,
                    costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(propfindStart),
                    resourceCount = resources.size
                )
                return@withContext resources
            }
        }

    private suspend fun executeRequest(root: LibraryRootEntity, request: Request): Response =
        withContext(Dispatchers.IO) {
            executeRequestBlocking(root, request)
        }

    private fun executeRequestBlocking(root: LibraryRootEntity, request: Request): Response =
        try {
            UnsafeNetworkPolicy.requireCleartextHttpAllowed(
                url = request.url.toString(),
                settings = appSettingsRepository.cachedSettings,
                operation = "WebDAV ${request.method}"
            )
            getClient().newCall(request).execute()
        } catch (error: WebDavException) {
            throw error
        } catch (error: IOException) {
            val failure = RemoteAvailabilityMappingPolicy.fromTransportException(error)
            val diagnosticUrl = request.url.redactedForLog()
            com.viel.aplayer.logger.VfsLogger.logWebDavError(
                url = diagnosticUrl,
                status = failure.availabilityStatus.name,
                errorClass = error.javaClass.simpleName
            )
            throw WebDavException(
                availabilityStatus = failure.availabilityStatus,
                message = if (failure.isTimeout) {
                    "WebDAV request timed out: $diagnosticUrl"
                } else {
                    "WebDAV network request failed: $diagnosticUrl"
                },
                cause = error
            )
        }

    private fun parsePropfindResponse(root: LibraryRootEntity, xml: String): List<WebDavResource> =
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val document = factory.newDocumentBuilder().parse(InputSource(xml.reader()))
            val responseNodes = document.documentElement.elementsByLocalName("response")
            responseNodes.mapNotNull { response ->
                if (!response.hasSuccessfulResponseStatus()) return@mapNotNull null
                val href = response.firstText("href") ?: return@mapNotNull null
                val sourcePath = sourcePathFromHref(root, href)
                val prop = response.successfulPropElement() ?: return@mapNotNull null
                val isDirectory = prop.elementsByLocalName("collection").isNotEmpty() || href.endsWith("/")
                val size = prop.firstText("getcontentlength")?.toLongOrNull() ?: 0L
                val lastModified = parseWebDavDate(prop.firstText("getlastmodified"))
                val etag = prop.firstText("getetag")?.trim()?.trim('"')
                val mimeType = prop.firstText("getcontenttype")
                WebDavResource(
                    sourcePath = sourcePath,
                    href = href,
                    uri = urlFor(root, sourcePath, directory = isDirectory).toString(),
                    displayName = sourcePath.substringAfterLast('/').ifBlank { root.displayName },
                    isDirectory = isDirectory,
                    fileSize = size,
                    lastModified = lastModified,
                    etag = etag,
                    mimeType = mimeType
                )
            }
        } catch (error: Exception) {
            throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                message = "WebDAV PROPFIND XML parse failed",
                cause = error
            )
        }

    private fun Element.hasSuccessfulResponseStatus(): Boolean {
        val status = directFirstText("status") ?: return true
        return status.isSuccessfulWebDavStatus()
    }

    private fun Element.successfulPropElement(): Element? {
        val propstats = directElementsByLocalName("propstat")
        if (propstats.isEmpty()) {
            return directElementsByLocalName("prop").firstOrNull()
        }
        return propstats
            .firstOrNull { propstat -> propstat.directFirstText("status")?.isSuccessfulWebDavStatus() == true }
            ?.directElementsByLocalName("prop")
            ?.firstOrNull()
    }

    private fun String.isSuccessfulWebDavStatus(): Boolean {
        val statusCode = HTTP_STATUS_LINE_PATTERN.matchEntire(trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return false
        return statusCode in 200..299
    }

    private fun Request.Builder.applyAuth(root: LibraryRootEntity): Request.Builder = apply {
        val credential = credentialStore.get(root.credentialId)
        if (credential != null && (credential.username.isNotBlank() || credential.password.isNotBlank())) {
            header("Authorization", Credentials.basic(credential.username, credential.password, Charsets.UTF_8))
        }
    }

    private fun Response.toWebDavException(method: String): WebDavException {
        val status = RemoteAvailabilityMappingPolicy.fromHttpStatus(
            statusCode = code,
            protocol = RemoteAvailabilityProtocol.WEBDAV
        )
        return WebDavException(
            availabilityStatus = status,
            message = "WebDAV $method failed with HTTP $code for ${request.url.redactedForLog()}"
        )
    }

    private fun Response.requireMatchingContentRange(start: Long, end: Long?, operation: String) {
        if (code != HTTP_PARTIAL_CONTENT) return
        val parsed = header("Content-Range")?.parseContentRangeWindow()
        val endMatches = end == null || parsed?.end == end
        if (parsed?.start != start || !endMatches) {
            throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                message = "WebDAV $operation returned mismatched Content-Range"
            )
        }
    }

    private fun String.parseContentRangeWindow(): ContentRangeWindow? {
        val match = CONTENT_RANGE_PATTERN.matchEntire(trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (start > end) return null
        return ContentRangeWindow(start = start, end = end)
    }

    private fun urlFor(root: LibraryRootEntity, sourcePath: String, directory: Boolean): HttpUrl {
        val base = root.sourceUri.toHttpUrlOrNull()
            ?: throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                message = "Invalid WebDAV sourceUri: ${root.sourceUri}"
            )
        val builder = base.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .encodedPath("/")
        val segments = sourceRootSegments(root) + normalizeSourcePath(sourcePath).segments()
        segments.forEach { segment -> builder.addPathSegment(segment) }
        if (directory && segments.isNotEmpty()) {
            builder.addPathSegment("")
        }
        return builder.build()
    }

    /**
     * Remove username, password, query, and fragment from diagnostic URLs.
     *
     * Provider exceptions use this view so timeout and HTTP failure messages never echo URL-embedded credentials.
     */
    private fun HttpUrl.redactedForLog(): String =
        newBuilder().username("").password("").query(null).fragment(null).build().toString()

    private fun rootFallbackResource(root: LibraryRootEntity): WebDavResource =
        WebDavResource(
            sourcePath = "",
            href = urlFor(root, "", directory = true).toString(),
            uri = urlFor(root, "", directory = true).toString(),
            displayName = root.displayName,
            isDirectory = true,
            fileSize = 0L,
            lastModified = 0L,
            etag = null,
            mimeType = null
        )

    private fun WebDavResource.toSourceNode(root: LibraryRootEntity): SourceNode {
        val parentSourcePath = parentSourcePathOf(sourcePath)
        val identity = etag?.takeIf { it.isNotBlank() } ?: sourcePath.ifBlank { root.id }
        return SourceNode(
            root = root,
            metadata = SourceFileMetadata(
                sourcePath = sourcePath,
                identity = identity,
                parentSourcePath = parentSourcePath,
                parentIdentity = parentSourcePath.ifBlank { root.id },
                displayName = displayName,
                isDirectory = isDirectory,
                fileSize = fileSize,
                lastModified = lastModified,
                etag = etag,
                mimeType = mimeType
            ),
            providerHandle = this
        )
    }

    private fun sourcePathFromHref(root: LibraryRootEntity, href: String): String {
        val decodedPath = normalizeSourcePath(href.toUri().path.orEmpty())
        val rootPrefix = sourceRootSegments(root).joinToString("/")
        return when {
            rootPrefix.isBlank() -> decodedPath
            decodedPath == rootPrefix -> ""
            decodedPath.startsWith("$rootPrefix/") -> decodedPath.removePrefix("$rootPrefix/").trim('/')
            else -> decodedPath
        }
    }

    private fun sourceRootSegments(root: LibraryRootEntity): List<String> {
        val sourceUriPath = normalizeSourcePath(root.sourceUri.toUri().path.orEmpty())
        return (sourceUriPath.segments() + normalizeSourcePath(root.basePath).segments())
            .filter { it.isNotBlank() }
    }

    private fun parentSourcePathOf(path: String): String =
        normalizeSourcePath(path).substringBeforeLast('/', missingDelimiterValue = "")

    private fun normalizeSourcePath(path: String): String =
        Uri.decode(path).replace('\\', '/').trim().trim('/')

    private fun String.segments(): List<String> =
        split('/').filter { it.isNotBlank() }

    private fun parseWebDavDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            WEB_DAV_DATE_FORMAT.get()?.parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (stream.read() == -1) {
                throw EOFException("WebDAV stream ended before requested offset")
            } else {
                remaining--
            }
        }
    }

    private fun boundedRangeEnd(file: SourceNode, offset: Long, length: Int): Long? {
        val knownSize = file.metadata.fileSize
        if (knownSize in 1..offset) return null
        val rawEnd = offset.saturatingAdd(length.toLong() - 1L)
        return if (knownSize > 0L) minOf(rawEnd, knownSize - 1L) else rawEnd
    }

    private fun Long.saturatingAdd(value: Long): Long =
        if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

    private fun InputStream.readAtMost(length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val output = ByteArrayOutputStream(length.coerceAtMost(DEFAULT_RANGE_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_RANGE_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun Element.elementsByLocalName(localName: String): List<Element> {
        val namespaced = getElementsByTagNameNS("*", localName).toElements()
        return namespaced.ifEmpty { getElementsByTagName(localName).toElements() }
    }

    private fun Element.firstText(localName: String): String? =
        elementsByLocalName(localName)
            .firstOrNull()
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun Element.directElementsByLocalName(localName: String): List<Element> =
        (0 until childNodes.length).mapNotNull { index ->
            val child = childNodes.item(index)
            val childName = child.localName ?: child.nodeName
            if (child.nodeType == Node.ELEMENT_NODE && childName == localName) child as? Element else null
        }

    private fun Element.directFirstText(localName: String): String? =
        directElementsByLocalName(localName)
            .firstOrNull()
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun NodeList.toElements(): List<Element> =
        (0 until length).mapNotNull { index -> item(index) as? Element }

    private companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_MULTI_STATUS = 207
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val DEFAULT_RANGE_BUFFER_SIZE = 16 * 1024
        private const val MAX_PROPFIND_RESPONSE_SIZE = 8 * 1024 * 1024
        private val CONTENT_RANGE_PATTERN = Regex("""^bytes\s+(\d+)-(\d+)/(?:\d+|\*)$""")
        private val HTTP_STATUS_LINE_PATTERN = Regex("""^HTTP/\S+\s+(\d{3})(?:\s+.*)?$""")

        private val WEB_DAV_DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
        }
    }
}

/**
 * Binds Response lifetime to InputStream close requests.
 * Releases underlying response connections to protect sockets from leaks during seek/track transition.
 */
private class ResponseClosingInputStream(
    private val delegate: InputStream,
    private val response: Response
) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray): Int = delegate.read(b)
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun skip(n: Long): Long = delegate.skip(n)
    override fun available(): Int = delegate.available()
    override fun mark(readlimit: Int) = delegate.mark(readlimit)
    override fun reset() = delegate.reset()
    override fun markSupported(): Boolean = delegate.markSupported()

    override fun close() {
        response.use { _ ->
            delegate.close()
        }
    }
}

/**
 * Converts stream bytes to String up to a fixed limit.
 * Enforces size checks using 4KB chunks, aborting connection when limit is breached.
 */
private fun InputStream.readStringWithLimit(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var totalRead = 0
    while (true) {
        val read = this.read(buffer)
        if (read == -1) break
        totalRead += read
        if (totalRead > maxBytes) {
            throw IOException("WebDAV PROPFIND response exceeded secure limit of $maxBytes bytes")
        }
        output.write(buffer, 0, read)
    }
    return output.toString("UTF-8")
}
