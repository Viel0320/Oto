package com.viel.aplayer.library.vfs

import android.content.Context
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.library.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.sourceProvider.SourceNode
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯读取门面。
 *
 * 详尽的中文注释：这个类现在只负责：
 * 1. 按 `rootId + sourcePath` 定位文件
 * 2. 提供 `open / listChildren / readRange`
 * 3. 为 WebDAV 的小片段读取构造直连节点
 *
 * 它不再暴露任何音频元数据、章节、封面相关 helper。
 * 这些格式逻辑已经全部回收到各自 parser 与 MetadataResolver 内部。
 */
class VfsFileReader(
    context: Context,
    private val libraryRootDao: LibraryRootDao? = null,
    private val rootsById: Map<String, LibraryRootEntity> = emptyMap()
) {
    private val vfs = VirtualFileSystem(LibrarySourceProviderFactory(context.applicationContext))
    // 详尽的中文注释：parser 在一次解析中会多次命中同一个 root，这里保留 root 级缓存以减少 Room 查询。
    private val rootCache = ConcurrentHashMap<String, LibraryRootEntity>()

    suspend fun open(file: FileRef): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        return vfs.openInputStream(root, VfsPath(file.sourcePath))
    }

    suspend fun open(file: FileRef, offset: Long): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        return vfs.openInputStream(root, VfsPath(file.sourcePath), offset)
    }

    suspend fun open(file: BookFileEntity): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        return vfs.openInputStream(root, VfsPath(file.sourcePath))
    }

    suspend fun open(file: BookFileEntity, offset: Long): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        return vfs.openInputStream(root, VfsPath(file.sourcePath), offset)
    }

    suspend fun open(node: VfsNode): InputStream? =
        vfs.openInputStream(node)

    suspend fun listChildren(rootId: String, sourcePath: String): List<VfsNode> {
        val root = rootFor(rootId) ?: return emptyList()
        val directory = vfs.resolve(root, VfsPath(sourcePath)) ?: return emptyList()
        return vfs.listChildren(directory)
    }

    suspend fun readRange(file: FileRef, offset: Long, length: Int): ByteArray? {
        val root = rootFor(file.rootId) ?: return null
        // 详尽的中文注释：扫描期 FileRef 已经带着完整的路径和大小信息，
        // WebDAV 下直接拼瞬时 VfsNode 能避免每次 readRange 之前再走一次 resolve/PROPFIND。
        directRangeNode(root, file)?.let { node ->
            return vfs.readRange(node, offset, length)
        }
        return vfs.readRange(root, VfsPath(file.sourcePath), offset, length)
    }

    suspend fun readRange(file: BookFileEntity, offset: Long, length: Int): ByteArray? {
        val root = rootFor(file.rootId) ?: return null
        // 详尽的中文注释：已入库文件同样只依赖持久化的 VFS 字段定位，
        // 这样播放后封面恢复、编辑页重建等流程也不会回到旧 URI 路径。
        directRangeNode(root, file)?.let { node ->
            return vfs.readRange(node, offset, length)
        }
        return vfs.readRange(root, VfsPath(file.sourcePath), offset, length)
    }

    private fun directRangeNode(root: LibraryRootEntity, file: FileRef): VfsNode? {
        if (LibrarySourceKind.from(root.sourceType) != LibrarySourceKind.WEBDAV) return null
        val metadata = SourceFileMetadata(
            uri = file.sourcePath,
            sourcePath = file.sourcePath,
            identity = file.sourceIdentity,
            parentSourcePath = file.parentSourcePath,
            parentIdentity = file.parentSourceIdentity,
            displayName = file.displayName,
            isDirectory = false,
            fileSize = file.fileSize,
            lastModified = file.lastModified,
            etag = file.etag
        )
        val sourceNode = SourceNode(root = root, metadata = metadata)
        return VfsNode(root = root, path = VfsPath(file.sourcePath), metadata = metadata, sourceNode = sourceNode)
    }

    private fun directRangeNode(root: LibraryRootEntity, file: BookFileEntity): VfsNode? {
        if (LibrarySourceKind.from(root.sourceType) != LibrarySourceKind.WEBDAV) return null
        val parentSourcePath = file.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val metadata = SourceFileMetadata(
            uri = file.sourcePath,
            sourcePath = file.sourcePath,
            identity = file.sourceIdentity,
            parentSourcePath = parentSourcePath,
            parentIdentity = root.id,
            displayName = file.displayName,
            isDirectory = false,
            fileSize = file.fileSize,
            lastModified = file.lastModified,
            etag = file.etag
        )
        val sourceNode = SourceNode(root = root, metadata = metadata)
        return VfsNode(root = root, path = VfsPath(file.sourcePath), metadata = metadata, sourceNode = sourceNode)
    }

    private suspend fun rootFor(rootId: String): LibraryRootEntity? {
        rootsById[rootId]?.let { return it }
        rootCache[rootId]?.let { return it }
        return libraryRootDao?.getRootById(rootId)?.also { root ->
            rootCache[rootId] = root
        }
    }
}
