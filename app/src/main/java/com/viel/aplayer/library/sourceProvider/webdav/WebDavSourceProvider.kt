package com.viel.aplayer.library.sourceProvider.webdav

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.sourceProvider.LibrarySourceProvider
import com.viel.aplayer.library.sourceProvider.SourceCapabilities
import com.viel.aplayer.library.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.sourceProvider.SourceNode
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 为每一次改动添加详尽的中文注释：WebDavException 将 HTTP/网络错误先映射成统一可用性状态，避免上层解析 OkHttp 异常细节。
class WebDavException(
    val availabilityStatus: String,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

// 为每一次改动添加详尽的中文注释：WebDavResource 是 Provider 内部资源模型，只在转换成 SourceNode 前承载 HTTP href/etag 等协议细节。
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

// 为每一次改动添加详尽的中文注释：WebDAV Provider 使用 OkHttp 实现 PROPFIND、GET 与 Range 读取，是远程标准件的第一条真实接入路径。
class WebDavSourceProvider(private val context: Context) : LibrarySourceProvider {
    override val kind: LibrarySourceKind = LibrarySourceKind.WEBDAV
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsDirectoryListing = true,
        supportsLastModified = true,
        supportsEtag = true,
        supportsRangeRead = true
    )

    // 为每一次改动添加详尽的中文注释：OkHttpClient 在 Provider 生命周期内复用连接池，减少 WebDAV 多目录扫描时的 TCP 握手开销。
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val credentialStore = WebDavCredentialStore(context.applicationContext)

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
                // 为每一次改动添加详尽的中文注释：播放器 seek 会传入 offset，WebDAV 通过 Range 直接从远端目标字节开始读。
                if (offset > 0L) header("Range", "bytes=$offset-")
            }
            .build()
        val response = executeRequest(request)
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
        val body = response.body ?: run {
            response.close()
            throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                message = "WebDAV GET returned an empty body: ${file.metadata.sourcePath}"
            )
        }
        return body.byteStream().also { stream ->
            // 为每一次改动添加详尽的中文注释：少数服务器忽略 Range 并返回 200，此处保留本地 skip 兜底以维持播放器语义。
            if (offset > 0L && response.code == HTTP_OK) {
                // 为每一次改动添加详尽的中文注释：fallback skip 也会阻塞读取远程字节，必须放在 IO 线程执行，不能继承 UI 调用方线程。
                withContext(Dispatchers.IO) { runCatching { skipFully(stream, offset) } }
                    .onFailure {
                        stream.close()
                        throw WebDavException(
                            availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                            message = "WebDAV fallback skip failed: ${file.metadata.sourcePath}",
                            cause = it
                        )
                    }
            }
        }
    }

    override suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            // 为每一次改动添加详尽的中文注释：元数据帧读取必须发送闭区间 Range，严禁复用播放器的 bytes=offset- 开口请求。
            if (file.metadata.isDirectory || offset < 0L || length <= 0) return@withContext ByteArray(0)
            val rangeEnd = boundedRangeEnd(file, offset, length) ?: return@withContext null
            val request = Request.Builder()
                .url(urlFor(file.root, file.metadata.sourcePath, directory = false))
                .get()
                .header("Range", "bytes=$offset-$rangeEnd")
                .applyAuth(file.root)
                .build()
            executeRequestBlocking(request).use { response ->
                when {
                    response.code == HTTP_RANGE_NOT_SATISFIABLE -> return@withContext null
                    response.code == HTTP_OK -> {
                        // 为每一次改动添加详尽的中文注释：服务器忽略闭区间 Range 时会返回完整文件，元数据解析必须立即拒绝并关闭响应。
                        return@withContext null
                    }
                    !response.isSuccessful -> throw response.toWebDavException("GET Range")
                }
                val body = response.body ?: return@withContext null
                body.byteStream().use { stream -> stream.readAtMost(length) }
            }
        }

    override suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor? {
        // 为每一次改动添加详尽的中文注释：WebDAV 远程来源不再提供整文件 FD；元数据、封面和播放都必须经由 VFS stream/path 与 Range 能力。
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
            // 为每一次改动添加详尽的中文注释：PROPFIND 包含同步网络请求和 XML body 读取，必须整体固定在 IO 线程，避免播放器/字幕回退从 Main 调用时崩溃。
            val url = urlFor(root, sourcePath, directory = true)
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_BODY)
                .header("Depth", depth)
                .header("Accept", "application/xml,text/xml,*/*")
                .applyAuth(root)
                .build()
            executeRequestBlocking(request).use { response ->
                if (response.code == HTTP_NOT_FOUND) {
                    throw response.toWebDavException("PROPFIND")
                }
                if (response.code != HTTP_MULTI_STATUS && !response.isSuccessful) {
                    throw response.toWebDavException("PROPFIND")
                }
                val xml = response.body?.string().orEmpty()
                if (xml.isBlank()) {
                    return@withContext listOf(rootFallbackResource(root))
                }
                return@withContext parsePropfindResponse(root, xml)
            }
        }

    private suspend fun executeRequest(request: Request): Response =
        withContext(Dispatchers.IO) {
            // 为每一次改动添加详尽的中文注释：GET/Range 连接建立是 OkHttp 阻塞调用，统一转入 IO 线程后再把响应流交给上层读取。
            executeRequestBlocking(request)
        }

    private fun executeRequestBlocking(request: Request): Response =
        try {
            client.newCall(request).execute()
        } catch (error: WebDavException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw WebDavException(
                availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                message = "WebDAV request timed out: ${request.url}",
                cause = error
            )
        } catch (error: IOException) {
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
                // 为每一次改动添加详尽的中文注释：禁用外部实体，避免解析 WebDAV XML 时触发不必要的本地/网络实体加载。
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
            // 为每一次改动添加详尽的中文注释：认证头只在请求发起前临时拼装，数据库仍只保存 credentialId。
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
        val decodedPath = normalizeSourcePath(Uri.parse(href).path.orEmpty())
        val rootPrefix = sourceRootSegments(root).joinToString("/")
        return when {
            rootPrefix.isBlank() -> decodedPath
            decodedPath == rootPrefix -> ""
            decodedPath.startsWith("$rootPrefix/") -> decodedPath.removePrefix("$rootPrefix/").trim('/')
            else -> decodedPath
        }
    }

    private fun sourceRootSegments(root: LibraryRootEntity): List<String> {
        val sourceUriPath = normalizeSourcePath(Uri.parse(root.sourceUri).path.orEmpty())
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
            // 为每一次改动添加详尽的中文注释：ThreadLocal 初始化器保证日期格式器非空，这里显式断言以消除 Kotlin 平台类型告警。
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
        if (knownSize > 0L && offset >= knownSize) return null
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
        return if (namespaced.isNotEmpty()) namespaced else getElementsByTagName(localName).toElements()
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
        private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        private const val HTTP_OK = 200
        private const val HTTP_MULTI_STATUS = 207
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_GATEWAY_TIMEOUT = 504
        private const val DEFAULT_RANGE_BUFFER_SIZE = 16 * 1024

        // 为每一次改动添加详尽的中文注释：SimpleDateFormat 非线程安全，用 ThreadLocal 支撑 OkHttp/扫描并发回调下的日期解析。
        private val WEB_DAV_DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
        }
    }
}
