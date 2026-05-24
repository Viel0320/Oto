package com.viel.aplayer.library.source

import android.content.Context
import android.net.Uri
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
        // 未知旧数据默认按 SAF 处理，保证第一阶段架构改造不改变现有本地库行为。
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

// 统一文件元数据，扫描、缓存和可用性检测都依赖它，而不是直接依赖 DocumentFile。
data class SourceFileMetadata(
    val uri: String,
    val sourcePath: String,
    val identity: String,
    val parentUri: String,
    val parentIdentity: String,
    val displayName: String,
    val isDirectory: Boolean,
    val fileSize: Long,
    val lastModified: Long,
    val etag: String? = null,
    val mimeType: String? = null
)

// 单个来源节点的通用视图；第一阶段保留 nativeDocumentFile 只为旧解析器桥接，后续切片会把解析器改到 VFS 流读取。
data class SourceNode(
    val root: LibraryRootEntity,
    val metadata: SourceFileMetadata,
    val nativeDocumentFile: DocumentFile? = null,
    val nativeParentDocumentFile: DocumentFile? = null
)

// LibrarySourceProvider 是未来 WebDAV/SMB/S3 等远程标准件的核心接口；扫描器只面向它工作。
interface LibrarySourceProvider {
    val kind: LibrarySourceKind
    val capabilities: SourceCapabilities

    suspend fun rootDirectory(root: LibraryRootEntity): SourceNode?
    suspend fun listChildren(directory: SourceNode): List<SourceNode>
    suspend fun openInputStream(file: SourceNode): InputStream?
    suspend fun exists(node: SourceNode): Boolean
}

// SAF Provider 将所有 DocumentFile 细节限制在本类内部，是现有功能不变接入标准件的第一步。
class SafSourceProvider(private val context: Context) : LibrarySourceProvider {
    override val kind: LibrarySourceKind = LibrarySourceKind.SAF
    override val capabilities: SourceCapabilities = SourceCapabilities()

    override suspend fun rootDirectory(root: LibraryRootEntity): SourceNode? {
        val treeUri = root.treeUri.ifBlank { root.sourceUri }
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: return null
        if (!rootDoc.exists()) return null
        return rootDoc.toNode(root = root, parent = null, relativePath = "")
    }

    override suspend fun listChildren(directory: SourceNode): List<SourceNode> {
        val documentFile = directory.nativeDocumentFile ?: return emptyList()
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
                relativePath = childRelativePath
            )
        }
    }

    override suspend fun openInputStream(file: SourceNode): InputStream? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(file.metadata.uri)) }.getOrNull()

    override suspend fun exists(node: SourceNode): Boolean =
        node.nativeDocumentFile?.exists() == true

    private fun DocumentFile.toNode(
        root: LibraryRootEntity,
        parent: DocumentFile?,
        relativePath: String
    ): SourceNode {
        val fileUri = uri.toString()
        val parentUri = parent?.uri?.toString() ?: root.treeUri
        val identity = uri.lastPathSegment ?: fileUri
        val parentIdentity = parent?.uri?.lastPathSegment ?: root.id
        return SourceNode(
            root = root,
            metadata = SourceFileMetadata(
                uri = fileUri,
                sourcePath = relativePath,
                identity = identity,
                parentUri = parentUri,
                parentIdentity = parentIdentity,
                displayName = name ?: relativePath.substringAfterLast('/'),
                isDirectory = isDirectory,
                fileSize = length(),
                lastModified = lastModified(),
                mimeType = type
            ),
            nativeDocumentFile = this,
            nativeParentDocumentFile = parent
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
