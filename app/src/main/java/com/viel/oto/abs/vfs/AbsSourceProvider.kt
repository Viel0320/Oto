package com.viel.oto.abs.vfs

import android.content.Context
import android.os.ParcelFileDescriptor
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiError
import com.viel.oto.abs.net.AbsAuth
import com.viel.oto.abs.net.AbsAuthInterceptor
import com.viel.oto.abs.net.AbsTokenRefreshClient
import com.viel.oto.abs.net.AbsTokenRefreshResult
import com.viel.oto.abs.net.AbsUrlResolver
import com.viel.oto.abs.net.RealAbsApiClient
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.availability.RemoteAvailabilityMappingPolicy
import com.viel.oto.library.availability.RemoteAvailabilityProtocol
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProvider
import com.viel.oto.library.vfs.sourceProvider.SourceCapabilities
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.oto.library.vfs.sourceProvider.SourceNode
import com.viel.oto.logger.AbsAuthLogger
import com.viel.oto.logger.AbsStreamLogger
import com.viel.oto.network.UnsafeNetworkPolicy
import com.viel.oto.shared.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class AbsSourceProvider(
    context: Context? = null,
    credentialStore: AbsCredentialStore? = null,
    settingsProvider: (() -> AppSettings)? = null,
    tokenRefreshClient: AbsTokenRefreshClient? = null,
    client: OkHttpClient? = null
) : LibrarySourceProvider, KoinComponent {
    private val injectedCredentialStore: AbsCredentialStore by inject()
    private val injectedSettingsRepository: AppSettingsRepository by inject()
    private val credentialStore = credentialStore ?: context
        ?.applicationContext
        ?.let { injectedCredentialStore }
        ?: injectedCredentialStore
    private val settingsProvider = settingsProvider ?: { injectedSettingsRepository.cachedSettings }
    private val tokenRefreshClient = tokenRefreshClient ?: RealAbsApiClient(
        credentialStore = this.credentialStore,
        settingsProvider = this.settingsProvider
    )
    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .addInterceptor(AbsAuthInterceptor(this.credentialStore))
        .build()
    override val kind: LibrarySourceKind = LibrarySourceKind.ABS
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsDirectoryListing = false,
        supportsLastModified = false,
        supportsEtag = false,
        supportsRangeRead = true
    )

    override suspend fun rootDirectory(root: LibraryRootEntity): SourceNode? = null

    override suspend fun resolve(root: LibraryRootEntity, sourcePath: String): SourceNode? {
        if (sourcePath.isBlank()) return null
        return SourceNode(
            root = root,
            metadata = SourceFileMetadata(
                sourcePath = sourcePath,
                identity = sourcePath,
                parentSourcePath = sourcePath.substringBeforeLast('/', ""),
                parentIdentity = root.id,
                displayName = sourcePath.substringAfterLast('/').ifBlank { root.displayName },
                isDirectory = false,
                fileSize = 0L,
                lastModified = 0L,
                etag = null,
                mimeType = null
            )
        )
    }

    override suspend fun listChildren(directory: SourceNode): List<SourceNode> = emptyList()

    override suspend fun openInputStream(file: SourceNode): InputStream =
        openInputStream(file, offset = 0L)

    override suspend fun openInputStream(file: SourceNode, offset: Long): InputStream {
        if (offset < 0L) {
            throw AbsApiError(
                code = "NEGATIVE_OFFSET",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                message = "ABS offset must not be negative"
            )
        }
        val openStart = AbsStreamLogger.mark()
        AbsStreamLogger.logOpenStart(rootId = file.root.id, sourcePath = file.metadata.sourcePath, offset = offset)
        return withContext(Dispatchers.IO) {
            val response = executeRequest(
                file = file,
                method = "GET",
                extraHeaders = if (offset > 0L) mapOf("Range" to "bytes=$offset-") else emptyMap()
            )
            if (offset > 0L && response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                response.close()
                AbsStreamLogger.logOpenFailure(
                    rootId = file.root.id,
                    sourcePath = file.metadata.sourcePath,
                    offset = offset,
                    httpCode = HTTP_RANGE_NOT_SATISFIABLE,
                    costMs = AbsStreamLogger.elapsedMs(openStart),
                    errorClass = "AbsApiError",
                    message = "RANGE_NOT_SATISFIABLE"
                )
                throw AbsApiError(
                    code = "RANGE_NOT_SATISFIABLE",
                    httpStatus = response.code,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    message = "ABS range request is out of bounds"
                )
            }
            if (offset > 0L && response.isSuccessful && !response.isTrustedOffsetResponse(offset)) {
                response.close()
                AbsStreamLogger.logOpenFailure(
                    rootId = file.root.id,
                    sourcePath = file.metadata.sourcePath,
                    offset = offset,
                    httpCode = response.code,
                    costMs = AbsStreamLogger.elapsedMs(openStart),
                    errorClass = "AbsApiError",
                    message = "RANGE_IGNORED"
                )
                throw AbsApiError(
                    code = "RANGE_IGNORED",
                    httpStatus = response.code,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
                    message = "ABS range request was ignored by the server"
                )
            }
            if (!response.isSuccessful) {
                val error = response.toAbsApiError("GET")
                response.close()
                AbsStreamLogger.logOpenFailure(
                    rootId = file.root.id,
                    sourcePath = file.metadata.sourcePath,
                    offset = offset,
                    httpCode = response.code,
                    costMs = AbsStreamLogger.elapsedMs(openStart),
                    errorClass = error::class.java.simpleName,
                    message = error.message
                )
                throw error
            }
            AbsStreamLogger.logOpenSuccess(
                rootId = file.root.id,
                sourcePath = file.metadata.sourcePath,
                offset = offset,
                httpCode = response.code,
                costMs = AbsStreamLogger.elapsedMs(openStart)
            )
            ResponseClosingInputStream(response.body.byteStream(), response)
        }
    }

    override suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            if (offset < 0L || length <= 0) return@withContext ByteArray(0)
            val rangeStart = AbsStreamLogger.mark()
            AbsStreamLogger.logRangeReadStart(rootId = file.root.id, sourcePath = file.metadata.sourcePath, offset = offset, length = length)
            val requestRangeEnd = boundedRangeEnd(offset, length) ?: return@withContext ByteArray(0)
            val response = executeRequest(
                file = file,
                method = "GET",
                extraHeaders = mapOf("Range" to "bytes=$offset-$requestRangeEnd")
            )
            response.use { httpResponse ->
                when {
                    httpResponse.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                        AbsStreamLogger.logRangeReadFailure(
                            rootId = file.root.id,
                            sourcePath = file.metadata.sourcePath,
                            offset = offset,
                            length = length,
                            httpCode = httpResponse.code,
                            costMs = AbsStreamLogger.elapsedMs(rangeStart),
                            errorClass = "AbsApiError",
                            message = "RANGE_NOT_SATISFIABLE"
                        )
                        return@withContext null
                    }
                    httpResponse.code == HTTP_OK -> {
                        AbsStreamLogger.logRangeReadFailure(
                            rootId = file.root.id,
                            sourcePath = file.metadata.sourcePath,
                            offset = offset,
                            length = length,
                            httpCode = httpResponse.code,
                            costMs = AbsStreamLogger.elapsedMs(rangeStart),
                            errorClass = "AbsApiError",
                            message = "RANGE_IGNORED"
                        )
                        return@withContext null
                    }
                    !httpResponse.isSuccessful -> {
                        val error = httpResponse.toAbsApiError("GET Range")
                        AbsStreamLogger.logRangeReadFailure(
                            rootId = file.root.id,
                            sourcePath = file.metadata.sourcePath,
                            offset = offset,
                            length = length,
                            httpCode = httpResponse.code,
                            costMs = AbsStreamLogger.elapsedMs(rangeStart),
                            errorClass = error::class.java.simpleName,
                            message = error.message
                        )
                        throw error
                    }
                }
                val bytes = httpResponse.body.bytes()
                AbsStreamLogger.logRangeReadSuccess(
                    rootId = file.root.id,
                    sourcePath = file.metadata.sourcePath,
                    offset = offset,
                    length = length,
                    actualBytes = bytes.size,
                    httpCode = httpResponse.code,
                    costMs = AbsStreamLogger.elapsedMs(rangeStart)
                )
                bytes
            }
        }

    override suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor? = null

    override suspend fun exists(node: SourceNode): Boolean =
        runCatching {
            val start = AbsStreamLogger.mark()
            withContext(Dispatchers.IO) {
                executeRequest(node, method = "HEAD").use { response ->
                    val readable = response.isSuccessful
                    AbsStreamLogger.logExistsCheck(
                        rootId = node.root.id,
                        sourcePath = node.metadata.sourcePath,
                        readable = readable,
                        costMs = AbsStreamLogger.elapsedMs(start)
                    )
                    readable
                }
            }
        }.getOrElse { error ->
            if (error is AbsApiError && error.httpStatus == HTTP_NOT_FOUND) false else throw error
        }

    suspend fun checkReadable(root: LibraryRootEntity, sourcePath: String): Boolean {
        val node = resolve(root, sourcePath) ?: return false
        return exists(node)
    }

    private suspend fun executeRequest(
        file: SourceNode,
        method: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Response {
        val credential = requireNotNull(credentialStore.get(file.root.credentialId)) {
            "Missing ABS credential for root ${file.root.id}"
        }
        if (credential.token.isBlank()) {
            AbsAuthLogger.logMissingCredential(path = "AbsSourceProvider.executeRequest", rootId = file.root.id, credentialId = file.root.credentialId)
        }
        val url = resolveContentUrl(credential.baseUrl, file.root, file.metadata.sourcePath)
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = url.toString(),
            settings = settingsProvider(),
            operation = "ABS media $method"
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .tag(AbsAuth::class.java, AbsAuth(token = credential.token, credentialId = file.root.credentialId))
        extraHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }
        when (method) {
            "HEAD" -> requestBuilder.head()
            else -> requestBuilder.get()
        }
        val response = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (error: IOException) {
            val failure = RemoteAvailabilityMappingPolicy.fromTransportException(error)
            AbsStreamLogger.logRequestFailure(method = method, url = url.toString(), errorClass = error.javaClass.simpleName, message = error.message)
            throw AbsApiError(
                code = failure.errorCode,
                availabilityStatus = failure.availabilityStatus,
                message = if (failure.isTimeout) "ABS media request timed out" else "ABS media request failed",
                cause = error
            )
        }
        if (response.code == HTTP_UNAUTHORIZED) {
            response.close()
            val credentialId = requireNotNull(file.root.credentialId) {
                "Missing ABS credential id for root ${file.root.id}"
            }
            val refreshResult = tokenRefreshClient.refreshToken(credentialId)
            throw AbsAuthExpiredException(
                credentialId = credentialId,
                rootId = file.root.id,
                refreshResult = refreshResult
            )
        }
        return response
    }

    internal fun resolveContentUrl(baseUrl: String, root: LibraryRootEntity, contentUrl: String): HttpUrl {
        val base = AbsUrlResolver.resolveBaseUrl(baseUrl)
        val source = contentUrl.trim()
        if (source.startsWith("/api/")) {
            val resolved = base.newBuilder()
                .addEncodedPathSegments(source.removePrefix("/"))
                .build()
            AbsStreamLogger.logResolveContentUrl(baseUrl = baseUrl, sourcePath = contentUrl, resolvedUrl = resolved.toString())
            return resolved
        }
        val absolute = source.toHttpUrlOrNull()
            ?: throw AbsApiError(
                code = "INVALID_CONTENT_URL",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                message = "Invalid ABS contentUrl"
            )
        val sameHost = absolute.host == base.host && absolute.port == base.port && absolute.scheme == base.scheme
        val basePath = base.encodedPath.trimEnd('/')
        val allowedPathPrefix = if (basePath.isBlank()) "/" else "$basePath/"
        if (!sameHost || (!absolute.encodedPath.startsWith(allowedPathPrefix) && absolute.encodedPath != basePath)) {
            AbsStreamLogger.logResolveContentUrlFailure(
                baseUrl = baseUrl,
                sourcePath = contentUrl,
                errorClass = "AbsApiError",
                message = "EXTERNAL_CONTENT_URL"
            )
            throw AbsApiError(
                code = "EXTERNAL_CONTENT_URL",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED,
                message = "ABS contentUrl points outside configured server"
            )
        }
        AbsStreamLogger.logResolveContentUrl(baseUrl = baseUrl, sourcePath = contentUrl, resolvedUrl = absolute.toString())
        return absolute
    }

    private fun Response.isTrustedOffsetResponse(offset: Long): Boolean {
        if (code == HTTP_PARTIAL_CONTENT) return true
        val contentRange = header("Content-Range") ?: return false
        return contentRange.startsWith("bytes $offset-")
    }

    private fun boundedRangeEnd(offset: Long, length: Int): Long? {
        if (offset < 0L || length <= 0) return null
        val delta = length.toLong() - 1L
        return if (offset > Long.MAX_VALUE - delta) Long.MAX_VALUE else offset + delta
    }

    private fun Response.toAbsApiError(method: String): AbsApiError {
        val availabilityStatus = RemoteAvailabilityMappingPolicy.fromHttpStatus(
            statusCode = code,
            protocol = RemoteAvailabilityProtocol.ABS
        )
        return AbsApiError(
            code = "HTTP_$code",
            httpStatus = code,
            availabilityStatus = availabilityStatus,
            message = "ABS media $method failed with HTTP $code"
        )
    }

    private companion object {
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

private object NoOpAbsTokenRefreshClient : AbsTokenRefreshClient {
    override suspend fun refreshToken(credentialId: String): AbsTokenRefreshResult = AbsTokenRefreshResult.Failed
}

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
