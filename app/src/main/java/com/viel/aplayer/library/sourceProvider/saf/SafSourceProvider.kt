package com.viel.aplayer.library.sourceProvider.saf

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.sourceProvider.LibrarySourceProvider
import com.viel.aplayer.library.sourceProvider.SourceCapabilities
import com.viel.aplayer.library.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.sourceProvider.SourceNode
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF provider。
 *
 * 详尽的中文注释：
 * 这里现在只保留一种直读策略：
 * `root.sourceUri + sourcePath -> buildDocumentUriUsingTree(...) -> ContentResolver.open*`
 *
 * 也就是说：
 * 1. 扫描/枚举阶段仍然可以复用 `DocumentFile`
 * 2. 真正的 open/readRange 不再依赖扫描期节点里保存的真实 `content://` uri
 * 3. 对 directRangeNode 和 resolve() 产出的节点统一走“重建真实路径”这一种方式
 */
class SafSourceProvider(private val context: Context) : LibrarySourceProvider {
    override val kind: LibrarySourceKind = LibrarySourceKind.SAF
    override val capabilities: SourceCapabilities = SourceCapabilities()

    override suspend fun rootDirectory(root: LibraryRootEntity): SourceNode? {
        val rootDoc = DocumentFile.fromTreeUri(context, root.sourceUri.toUri()) ?: return null
        if (!rootDoc.exists()) return null
        return rootDoc.toNode(root = root, parent = null, sourcePath = "")
    }

    override suspend fun resolve(root: LibraryRootEntity, sourcePath: String): SourceNode? {
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
        runCatching { context.contentResolver.openInputStream(buildDocumentUriFromPath(file.root, file.metadata.sourcePath)) }.getOrNull()

    override suspend fun openInputStream(file: SourceNode, offset: Long): InputStream? {
        if (offset <= 0L) return openInputStream(file)
        val pfd = runCatching {
            context.contentResolver.openFileDescriptor(buildDocumentUriFromPath(file.root, file.metadata.sourcePath), "r")
        }.getOrNull() ?: return null
        val input = FileInputStream(pfd.fileDescriptor)
        return try {
            withContext(Dispatchers.IO) {
                input.channel.position(offset)
            }
            object : FilterInputStream(input) {
                override fun close() {
                    pfd.use {
                        super.close()
                    }
                }
            }
        } catch (error: Exception) {
            runCatching { input.close() }
            runCatching { pfd.close() }
            null
        }
    }

    override suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        return openInputStream(file, offset)?.use { stream ->
            val buffer = ByteArray(length)
            var total = 0
            while (total < length) {
                val read = stream.read(buffer, total, length - total)
                if (read <= 0) break
                total += read
            }
            buffer.copyOf(total)
        }
    }

    override suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(buildDocumentUriFromPath(file.root, file.metadata.sourcePath), "r") }.getOrNull()

    override suspend fun exists(node: SourceNode): Boolean =
        runCatching {
            DocumentFile.fromSingleUri(context, buildDocumentUriFromPath(node.root, node.metadata.sourcePath))?.exists() == true
        }.getOrDefault(false)

    private fun buildDocumentUriFromPath(root: LibraryRootEntity, sourcePath: String) =
        root.sourceUri.toUri().let { treeUri ->
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val normalizedSourcePath = sourcePath
                .replace('\\', '/')
                .trim()
                .trim('/')
            val documentId = if (normalizedSourcePath.isBlank()) {
                treeDocumentId
            } else {
                "$treeDocumentId/$normalizedSourcePath"
            }
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        }

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
                // 详尽的中文注释：虽然这里仍然能拿到真实 `content://` uri，
                // 但它只用于本地 identity 推导，不再写入公共元数据对象，避免上层绕过 VFS/Provider。
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
