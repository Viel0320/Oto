package com.viel.aplayer.library.vfs.sourceProvider.webdav

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
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
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

// WebDavException (Maps HTTP and network errors to standardized availability statuses to avoid exposing OkHttp details upstream)
class WebDavException(
    val availabilityStatus: String,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

// WebDavResource (Internal provider model representing HTTP resources with href, etag, and protocol-specific details)
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

// WebDavSourceProvider (OkHttp-powered provider implementing PROPFIND, GET, and Range reads as the primary remote source path)
class WebDavSourceProvider(context: Context) : LibrarySourceProvider {
    override val kind: LibrarySourceKind = LibrarySourceKind.WEBDAV
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsDirectoryListing = true,
        supportsLastModified = true,
        supportsEtag = true,
        supportsRangeRead = true
    )

    private val appSettingsRepository = AppSettingsRepository.getInstance(context.applicationContext)

    // WebDAV Client Factory (Shares TLS bypass construction with settings connection tests)
    // The provider still decides whether insecure TLS is allowed for each root, but client construction no longer duplicates certificate logic.
    private val clientFactory = WebDavHttpClientFactory(
        connectTimeoutSeconds = 10,
        readTimeoutSeconds = 45,
        callTimeoutSeconds = 60
    )

    private val credentialStore = WebDavCredentialStore(context.applicationContext)

    // Resolve Client Policy (Selects trusted or insecure WebDAV client from the global settings switch)
    // Root-level TLS exceptions are intentionally ignored so certificate bypass behavior remains controlled by one user-visible setting.
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
                // Player seek requests include offsets; maps them to HTTP Range headers to stream directly from desired offsets.
                if (offset > 0L) header("Range", "bytes=$offset-")
            }
            .build()
        // Records the start timestamp of the GET/Range stream requests to calculate overall network latency.
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

        // Obtains the response payload body, verified as non-null.
        val body = response.body
        val stream = body.byteStream()

        // Falls back to skipping bytes locally if servers ignore Range headers and return HTTP 200, preserving seek semantics.
        if (offset > 0L && response.code == HTTP_OK) {
            // Fallback skip actions block on network reads; must execute on Dispatchers.IO instead of inheriting caller thread.
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

        // Success Logger (Records connection details after the offset seek and fallback skip complete successfully)
        com.viel.aplayer.logger.VfsLogger.logWebDavOpen(
            sourcePath = file.metadata.sourcePath,
            offset = offset,
            costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(openStart),
            httpCode = response.code,
            success = true
        )

        // Proxy Stream (Wraps native input stream to trigger response.close() on close, avoiding OkHttp socket leaks)
        return ResponseClosingInputStream(stream, response)
    }

    override suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            // Bounded Reads (Metadata range readers require closed intervals; open-ended bytes=offset- requests are prohibited)
            if (file.metadata.isDirectory || offset < 0L || length <= 0) return@withContext ByteArray(0)
            val rangeEnd = boundedRangeEnd(file, offset, length) ?: return@withContext null
            // Records the start timestamp of range segment queries.
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
                        // Logs out-of-bounds requests returning HTTP 416.
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
                        // Closes response immediately if servers return the whole file (HTTP 200) instead of the range.
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
                val body = response.body
                val result = body.byteStream().use { stream -> stream.readAtMost(length) }
                // Logs successful range query metrics with read byte counts.
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
        // File Descriptor Prevention (Remote resource limitation)
        // WebDAV remote source does not support file descriptor mapping; metadata, cover extraction, and streaming must utilize stream reading and Range capabilities.
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
            // Enforces Dispatchers.IO scope for PROPFIND network I/O and XML body parsing to protect the Main thread.
            // Tracks start timestamp to profile aggregate connection and parse times.
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
                // Size Check (Rejects response payload immediately if Content-Length exceeds security thresholds to prevent memory exhaustion)
                if (contentLength > MAX_PROPFIND_RESPONSE_SIZE) {
                    throw WebDavException(
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                        message = "WebDAV PROPFIND response content-length is too large: $contentLength bytes (limit: $MAX_PROPFIND_RESPONSE_SIZE)"
                    )
                }

                val xml = try {
                    // Safe Read (Reads stream with real-time limits to protect against chunked responses ignoring content length headers)
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
                    // Profile empty response latencies.
                    com.viel.aplayer.logger.VfsLogger.logWebDavPropfind(
                        sourcePath = sourcePath, depth = depth,
                        costMs = com.viel.aplayer.logger.VfsLogger.elapsedMs(propfindStart),
                        resourceCount = 1
                    )
                    return@withContext listOf(rootFallbackResource(root))
                }
                val resources = parsePropfindResponse(root, xml)
                // Profile complete request latencies with returned items.
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
            // GET and Range requests block on connection handshakes; switches context to IO threads to isolate blocks.
            executeRequestBlocking(root, request)
        }

    private fun executeRequestBlocking(root: LibraryRootEntity, request: Request): Response =
        try {
            // Unsafe Network Request Gate (Validate transport before sending WebDAV credentials or stream requests)
            // The provider checks every PROPFIND, GET, and Range request centrally so new WebDAV operations cannot bypass the cleartext policy.
            UnsafeNetworkPolicy.requireCleartextHttpAllowed(
                url = request.url.toString(),
                settings = appSettingsRepository.cachedSettings,
                operation = "WebDAV ${request.method}"
            )
            // Execute Request: Select client instance according to the global SSL verification setting and run network call.
            getClient().newCall(request).execute()
        } catch (error: WebDavException) {
            throw error
        } catch (error: SocketTimeoutException) {
            // Logs socket timeout details.
            com.viel.aplayer.logger.VfsLogger.logWebDavError(
                url = request.url.toString(),
                status = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                errorClass = error.javaClass.simpleName
            )
            throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                message = "WebDAV request timed out: ${request.url}",
                cause = error
            )
        } catch (error: IOException) {
            // Logs basic connection failures.
            com.viel.aplayer.logger.VfsLogger.logWebDavError(
                url = request.url.toString(),
                status = AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE,
                errorClass = error.javaClass.simpleName
            )
            throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE,
                message = "WebDAV network request failed: ${request.url}",
                cause = error
            )
        }

    private fun parsePropfindResponse(root: LibraryRootEntity, xml: String): List<WebDavResource> =
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Disables external entity resolution to protect parsing routines from local/remote exploit vectors.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val document = factory.newDocumentBuilder().parse(InputSource(xml.reader()))
            val responseNodes = document.documentElement.elementsByLocalName("response")
            responseNodes.mapNotNull { response ->
                val href = response.firstText("href") ?: return@mapNotNull null
                val sourcePath = sourcePathFromHref(root, href)
                val prop = response.elementsByLocalName("prop").firstOrNull()
                val isDirectory = prop?.elementsByLocalName("collection")?.isNotEmpty() == true || href.endsWith("/")
                val size = prop?.firstText("getcontentlength")?.toLongOrNull() ?: 0L
                val lastModified = parseWebDavDate(prop?.firstText("getlastmodified"))
                val etag = prop?.firstText("getetag")?.trim()?.trim('"')
                val mimeType = prop?.firstText("getcontenttype")
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

    private fun Request.Builder.applyAuth(root: LibraryRootEntity): Request.Builder = apply {
        val credential = credentialStore.get(root.credentialId)
        if (credential != null && (credential.username.isNotBlank() || credential.password.isNotBlank())) {
            // Authorization headers are compiled dynamically before dispatching, avoiding plain credentials storage in DB.
            header("Authorization", Credentials.basic(credential.username, credential.password, Charsets.UTF_8))
        }
    }

    private fun Response.toWebDavException(method: String): WebDavException {
        val status = when (code) {
            HTTP_UNAUTHORIZED -> AudiobookSchema.AvailabilityStatus.AUTH_FAILED
            HTTP_FORBIDDEN -> AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED
            HTTP_NOT_FOUND -> AudiobookSchema.AvailabilityStatus.NOT_FOUND
            HTTP_REQUEST_TIMEOUT, HTTP_GATEWAY_TIMEOUT -> AudiobookSchema.AvailabilityStatus.TIMEOUT
            in 500..599 -> AudiobookSchema.AvailabilityStatus.SERVER_ERROR
            else -> AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE
        }
        return WebDavException(
            availabilityStatus = status,
            message = "WebDAV $method failed with HTTP $code for ${request.url}"
        )
    }

    private fun urlFor(root: LibraryRootEntity, sourcePath: String, directory: Boolean): HttpUrl {
        val base = root.sourceUri.toHttpUrlOrNull()
            ?: throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                message = "Invalid WebDAV sourceUri: ${root.sourceUri}"
            )
        val builder = base.newBuilder()
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
            // ThreadLocal initializer guarantees non-null formatters; asserts explicitly to eliminate platform warning.
            WEB_DAV_DATE_FORMAT.get()!!.parse(value)?.time ?: 0L
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

    private fun NodeList.toElements(): List<Element> =
        (0 until length).mapNotNull { index -> item(index) as? Element }

    private companion object {
        private const val HTTP_OK = 200
        private const val HTTP_MULTI_STATUS = 207
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_GATEWAY_TIMEOUT = 504
        private const val DEFAULT_RANGE_BUFFER_SIZE = 16 * 1024
        // Safety Limit (Defines 8MB upper bound for PROPFIND responses to prevent out-of-memory states from large XML inputs)
        private const val MAX_PROPFIND_RESPONSE_SIZE = 8 * 1024 * 1024

        // SimpleDateFormat is thread-unsafe; uses ThreadLocal to support concurrent callback resolutions during scans.
        private val WEB_DAV_DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
        }
    }
}

/**
 * Stream Wrap Proxy (Binds Response lifetime to InputStream close requests)
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
        try {
            delegate.close()
        } finally {
            response.close()
        }
    }
}

/**
 * Bounded Read Utility (Converts stream bytes to String up to a fixed limit)
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
