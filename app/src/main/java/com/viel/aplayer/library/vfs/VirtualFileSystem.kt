package com.viel.aplayer.library.vfs

import android.os.ParcelFileDescriptor
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProvider
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import java.io.InputStream

// VfsPath 是跨协议路径的统一外壳；SAF 和 WebDAV 都通过它描述来源内路径。
data class VfsPath(val value: String) {
    val isRoot: Boolean get() = value.isBlank()
}

// VfsNode 是扫描、缓存和读取之间传递的标准节点，业务层不再持有 provider 原生文件对象。
data class VfsNode(
    val root: LibraryRootEntity,
    val path: VfsPath,
    val metadata: SourceFileMetadata,
    val sourceNode: SourceNode
)

// VFS 缓冲策略入口；第一阶段使用 NO_CACHE，后续 WebDAV 可切换目录元数据和小文件缓存。
enum class VfsCachePolicy {
    NO_CACHE,
    METADATA_ONLY,
    SMALL_FILE
}

// 元数据缓存接口先定义边界，SAF 第一阶段 no-op，WebDAV 第二阶段再落 Room/磁盘缓存实现。
interface VfsMetadataCache {
    suspend fun get(rootId: String, path: VfsPath): SourceFileMetadata?
    suspend fun put(rootId: String, metadata: SourceFileMetadata)
}

// no-op 缓存确保第一阶段不会改变 SAF 扫描的实时性和旧行为。
object NoOpVfsMetadataCache : VfsMetadataCache {
    override suspend fun get(rootId: String, path: VfsPath): SourceFileMetadata? = null
    override suspend fun put(rootId: String, metadata: SourceFileMetadata) = Unit
}

// VirtualFileSystem 统一封装 provider 访问；扫描器后续只依赖 VFS，不直接依赖 SAF 或 WebDAV。
class VirtualFileSystem(
    private val providerFactory: LibrarySourceProviderFactory,
    private val metadataCache: VfsMetadataCache = NoOpVfsMetadataCache
) {
    suspend fun root(root: LibraryRootEntity): VfsNode? {
        val provider = providerFactory.providerFor(root)
        return provider.rootDirectory(root)?.toVfsNode()
    }

    suspend fun resolve(root: LibraryRootEntity, path: VfsPath): VfsNode? {
        // 按 rootId/sourcePath 重新定位节点，替代业务层自行还原来源原生文件对象。
        val provider = providerFactory.providerFor(root)
        return provider.resolve(root, path.value)?.toVfsNode()
    }

    suspend fun listChildren(directory: VfsNode): List<VfsNode> {
        val provider = providerFactory.providerFor(directory.root)
        return provider.listChildren(directory.sourceNode).map { child ->
            metadataCache.put(directory.root.id, child.metadata)
            child.toVfsNode()
        }
    }

    suspend fun openInputStream(file: VfsNode): InputStream? =
        providerFor(file).openInputStream(file.sourceNode)

    suspend fun openInputStream(root: LibraryRootEntity, path: VfsPath): InputStream? =
        resolve(root, path)?.let { openInputStream(it) }

    // 播放器随机定位通过 VFS offset API 进入 Provider，远程来源可直接转成 Range 请求。
    suspend fun openInputStream(file: VfsNode, offset: Long): InputStream? =
        providerFor(file).openInputStream(file.sourceNode, offset)

    // 按 root/path 打开的 offset 流保持与普通流一致的寻址方式，不让播放层感知来源类型。
    suspend fun openInputStream(root: LibraryRootEntity, path: VfsPath, offset: Long): InputStream? =
        resolve(root, path)?.let { openInputStream(it, offset) }

    suspend fun readRange(file: VfsNode, offset: Long, length: Int): ByteArray? =
        // 元数据帧解析必须把 length 传到 Provider，WebDAV 才能发 bytes=start-end 而不是 start-。
        providerFor(file).readRange(file.sourceNode, offset, length)

    suspend fun readRange(root: LibraryRootEntity, path: VfsPath, offset: Long, length: Int): ByteArray? =
        // 按 root/path 做有界小片段读取，避免元数据读取复用播放器的开口 offset 流。
        resolve(root, path)?.let { readRange(it, offset, length) }

    suspend fun openFileDescriptor(file: VfsNode): ParcelFileDescriptor? =
        providerFor(file).openFileDescriptor(file.sourceNode)

    suspend fun openFileDescriptor(root: LibraryRootEntity, path: VfsPath): ParcelFileDescriptor? =
        resolve(root, path)?.let { openFileDescriptor(it) }

    suspend fun exists(node: VfsNode): Boolean =
        providerFor(node).exists(node.sourceNode)

    private fun providerFor(node: VfsNode): LibrarySourceProvider =
        providerFactory.providerFor(node.root)

    private fun SourceNode.toVfsNode(): VfsNode =
        VfsNode(
            root = root,
            path = VfsPath(metadata.sourcePath),
            metadata = metadata,
            sourceNode = this
        )
}
