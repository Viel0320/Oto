package com.viel.aplayer.library.vfs

import android.content.Context
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.library.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.sourceProvider.SourceNode
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯读取门面。
 *
 * 这个类现在只负责：
 * 1. 按 `rootId + sourcePath` 定位文件
 * 2. 提供 `open / listChildren / readRange`
 * 3. 对所有 provider 优先构造 directRangeNode，避免 range 读取前再走一遍 `resolve()`
 *
 * 它不再暴露任何音频元数据、章节、封面相关 helper。
 */
class VfsFileReader(
    context: Context,
    private val libraryRootDao: LibraryRootDao? = null,
    private val rootsById: Map<String, LibraryRootEntity> = emptyMap()
) {
    private val vfs = VirtualFileSystem(LibrarySourceProviderFactory(context.applicationContext))
    private val rootCache = ConcurrentHashMap<String, LibraryRootEntity>()

    suspend fun open(file: FileRef): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        // 详尽的中文注释：播放打开本地文件时，优先直接构造 VFS 节点，
        // 避免每次 `open()` 都先回到 `resolve(root, path)` 再逐段定位目录。
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node)
    }

    suspend fun open(file: FileRef, offset: Long): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        // 详尽的中文注释：seek / 切章节等带 offset 的播放打开同样优先走 direct node，
        // 让 provider 直接基于稳定定位字段执行随机打开，而不是先做一次额外 resolve。
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node, offset)
    }

    suspend fun open(file: BookFileEntity): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        // 详尽的中文注释：已入库播放文件也优先构造 direct node，
        // 这样 `BookFileEntity -> open()` 的热路径不会因为再次 resolve 而额外变慢。
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node)
    }

    suspend fun open(file: BookFileEntity, offset: Long): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        // 详尽的中文注释：本地播放的 offset 打开是最敏感的热路径之一，
        // 这里与 `readRange()` 保持一致，直接把逻辑定位字段交给 provider 执行打开。
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node, offset)
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
        // 现在无论是 SAF 还是 WebDAV，只要调用方已经持有稳定的文件定位字段，
        // 都优先构造 directRangeNode，避免 `VirtualFileSystem.readRange(root, path, ...)` 先触发 `resolve()`。
        val node = directRangeNode(root, file)
        return vfs.readRange(node, offset, length)
    }

    suspend fun readRange(file: BookFileEntity, offset: Long, length: Int): ByteArray? {
        val root = rootFor(file.rootId) ?: return null
        // 已入库文件同样优先走 directRangeNode，
        // 让封面恢复、播放后重解析等路径和扫描期使用相同的快路径。
        val node = directRangeNode(root, file)
        return vfs.readRange(node, offset, length)
    }

    private fun directOpenNode(root: LibraryRootEntity, file: FileRef): VfsNode =
        // 详尽的中文注释：打开流和读取 range 需要的是同一份稳定文件定位字段，
        // 因此直接复用相同的节点构造逻辑，避免维护两套几乎等价的 metadata 映射代码。
        directRangeNode(root, file)

    private fun directOpenNode(root: LibraryRootEntity, file: BookFileEntity): VfsNode =
        // 详尽的中文注释：BookFileEntity 版本同样复用 directRangeNode，
        // 让播放 open/readRange 在 VFS 层共享同一条“跳过 resolve”快路径。
        directRangeNode(root, file)

    private fun directRangeNode(root: LibraryRootEntity, file: FileRef): VfsNode {
        val metadata = SourceFileMetadata(
            // 这里只保留稳定的 VFS 逻辑定位字段，
            // 不再伪造任何 uri，也不再让 directRangeNode 携带可穿透到 provider 的真实地址。
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

    private fun directRangeNode(root: LibraryRootEntity, file: BookFileEntity): VfsNode {
        val parentSourcePath = file.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val metadata = SourceFileMetadata(
            // 数据库实体直构节点时同样只保留 sourcePath/identity 等稳定字段，
            // 让后续 readRange/open 统一由 provider 负责把逻辑路径映射回真实文件入口。
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
