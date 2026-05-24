package com.viel.aplayer.library.source

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import java.io.InputStream

// 通用来源类型的轻量封装，避免扫描器直接判断 WebDAV/SAF 等协议字符串。
enum class LibrarySourceKind(val schemaValue: String) {
    SAF(AudiobookSchema.LibrarySourceType.SAF),
    WEBDAV(AudiobookSchema.LibrarySourceType.WEBDAV);

    companion object {
        // 为每一次改动添加详尽的中文注释：未知来源类型暂按 SAF 处理，保证破坏性重建后的本地来源仍能被 Provider 接管。
        fun from(value: String): LibrarySourceKind =
            entries.firstOrNull { it.schemaValue == value } ?: SAF
    }
}

// 来源能力声明后续会承载 WebDAV Range、etag、lastModified 等差异；SAF 先声明当前可用的最小能力。
data class SourceCapabilities(
    val supportsDirectoryListing: Boolean = true,
    val supportsLastModified: Boolean = true,
    val supportsEtag: Boolean = false,
    val supportsRangeRead: Boolean = false
)

// 为每一次改动添加详尽的中文注释：统一文件元数据，扫描、缓存和可用性检测只依赖 VFS 路径和来源身份。
data class SourceFileMetadata(
    val uri: String,
    val sourcePath: String,
    val identity: String,
    val parentSourcePath: String,
    val parentIdentity: String,
    val displayName: String,
    val isDirectory: Boolean,
    val fileSize: Long,
    val lastModified: Long,
    val etag: String? = null,
    val mimeType: String? = null
)

// 为每一次改动添加详尽的中文注释：SourceNode 对外只暴露 providerHandle，不让业务层继续感知 SAF DocumentFile 类型。
data class SourceNode(
    val root: LibraryRootEntity,
    val metadata: SourceFileMetadata,
    val providerHandle: Any? = null
)

// LibrarySourceProvider 是未来 WebDAV/SMB/S3 等远程标准件的核心接口；扫描器只面向它工作。
interface LibrarySourceProvider {
    val kind: LibrarySourceKind
    val capabilities: SourceCapabilities

    suspend fun rootDirectory(root: LibraryRootEntity): SourceNode?
    suspend fun resolve(root: LibraryRootEntity, sourcePath: String): SourceNode?
    suspend fun listChildren(directory: SourceNode): List<SourceNode>
    suspend fun openInputStream(file: SourceNode): InputStream?
    suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor?
    suspend fun exists(node: SourceNode): Boolean
}

// 为每一次改动添加详尽的中文注释：SAF Provider 将 DocumentFile 限定在本类内部，外部统一通过 VFS path/stream API 访问。
class SafSourceProvider(private val context: Context) : LibrarySourceProvider {
    override val kind: LibrarySourceKind = LibrarySourceKind.SAF
    override val capabilities: SourceCapabilities = SourceCapabilities()

    override suspend fun rootDirectory(root: LibraryRootEntity): SourceNode? {
        val rootDoc = DocumentFile.fromTreeUri(context, root.sourceUri.toUri()) ?: return null
        if (!rootDoc.exists()) return null
        return rootDoc.toNode(root = root, parent = null, sourcePath = "")
    }

    override suspend fun resolve(root: LibraryRootEntity, sourcePath: String): SourceNode? {
        // 为每一次改动添加详尽的中文注释：按 VFS sourcePath 从根目录逐级解析文件，供封面、字幕和可用性检测复用。
        val rootNode = rootDirectory(root) ?: return null
        if (sourcePath.isBlank()) return rootNode
        val rootDoc = rootNode.providerHandle as? DocumentFile ?: return null
        val segments = sourcePath.split('/').filter { it.isNotBlank() }
        var current: DocumentFile? = rootDoc
        var parent: DocumentFile? = null
        var currentPath = ""
        for (segment in segments) {
            parent = current
            current = current?.findFile(segment)
            currentPath = if (currentPath.isBlank()) segment else "$currentPath/$segment"
            if (current == null) return null
        }
        return current?.toNode(root = root, parent = parent, sourcePath = currentPath)
    }

    override suspend fun listChildren(directory: SourceNode): List<SourceNode> {
        val documentFile = directory.providerHandle as? DocumentFile ?: return emptyList()
        return documentFile.listFiles().mapNotNull { child ->
            val childName = child.name ?: return@mapNotNull null
            val childRelativePath = if (directory.metadata.sourcePath.isBlank()) {
                childName
            } else {
                "${directory.metadata.sourcePath}/$childName"
            }
            child.toNode(
                root = directory.root,
                parent = documentFile,
                sourcePath = childRelativePath
            )
        }
    }

    override suspend fun openInputStream(file: SourceNode): InputStream? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(file.metadata.uri)) }.getOrNull()

    override suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(Uri.parse(file.metadata.uri), "r") }.getOrNull()

    override suspend fun exists(node: SourceNode): Boolean =
        (node.providerHandle as? DocumentFile)?.exists() == true

    private fun DocumentFile.toNode(
        root: LibraryRootEntity,
        parent: DocumentFile?,
        sourcePath: String
    ): SourceNode {
        val fileUri = uri.toString()
        val parentSourcePath = sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val identity = uri.lastPathSegment ?: fileUri
        val parentIdentity = parent?.uri?.lastPathSegment ?: root.id
        return SourceNode(
            root = root,
            metadata = SourceFileMetadata(
                uri = fileUri,
                sourcePath = sourcePath,
                identity = identity,
                parentSourcePath = parentSourcePath,
                parentIdentity = parentIdentity,
                displayName = name ?: sourcePath.substringAfterLast('/'),
                isDirectory = isDirectory,
                fileSize = length(),
                lastModified = lastModified(),
                mimeType = type
            ),
            providerHandle = this
        )
    }
}

// ProviderFactory 先只返回 SAF 实现；WebDAV 第二阶段只需要在这里挂接新 Provider。
class LibrarySourceProviderFactory(private val context: Context) {
    private val safProvider = SafSourceProvider(context)

    fun providerFor(root: LibraryRootEntity): LibrarySourceProvider =
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> safProvider
            LibrarySourceKind.WEBDAV -> error("WebDAV provider is not implemented in phase 1")
        }
}
