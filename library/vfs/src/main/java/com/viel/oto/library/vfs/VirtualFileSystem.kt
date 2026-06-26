package com.viel.oto.library.vfs

import android.os.ParcelFileDescriptor
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.vfs.cache.DirectoryListingCache
import com.viel.oto.library.vfs.cache.NoOpDirectoryListingCache
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProvider
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.oto.library.vfs.sourceProvider.SourceNode
import java.io.InputStream

data class VfsPath(val value: String) {
    val isRoot: Boolean get() = value.isBlank()
}

data class VfsNode(
    val root: LibraryRootEntity,
    val path: VfsPath,
    val metadata: SourceFileMetadata,
    val sourceNode: SourceNode
)

enum class VfsCachePolicy {
    NO_CACHE,
    METADATA_ONLY,
    SMALL_FILE
}

interface VfsMetadataCache {
    suspend fun get(rootId: String, path: VfsPath): SourceFileMetadata?
    suspend fun put(rootId: String, metadata: SourceFileMetadata)
}

object NoOpVfsMetadataCache : VfsMetadataCache {
    override suspend fun get(rootId: String, path: VfsPath): SourceFileMetadata? = null
    override suspend fun put(rootId: String, metadata: SourceFileMetadata) = Unit
}

class VirtualFileSystem private constructor(
    private val providerResolver: (LibraryRootEntity) -> LibrarySourceProvider,
    private val metadataCache: VfsMetadataCache = NoOpVfsMetadataCache,
    private val directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache
) {
    constructor(
        providerFactory: LibrarySourceProviderFactory,
        metadataCache: VfsMetadataCache = NoOpVfsMetadataCache,
        directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache
    ) : this(
        providerResolver = { root -> providerFactory.providerFor(root) },
        metadataCache = metadataCache,
        directoryListingCache = directoryListingCache
    )

    internal constructor(
        providerResolver: (LibraryRootEntity) -> LibrarySourceProvider,
        directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache
    ) : this(
        providerResolver = providerResolver,
        metadataCache = NoOpVfsMetadataCache,
        directoryListingCache = directoryListingCache
    )

    suspend fun root(root: LibraryRootEntity): VfsNode? {
        val provider = providerResolver(root)
        return provider.rootDirectory(root)?.toVfsNode()
    }

    suspend fun resolve(root: LibraryRootEntity, path: VfsPath): VfsNode? {
        val provider = providerResolver(root)
        return provider.resolve(root, path.value)?.toVfsNode()
    }

    suspend fun listChildren(directory: VfsNode): List<VfsNode> {
        directoryListingCache.getChildren(directory)?.let { cachedChildren ->
            return cachedChildren.map { metadata -> metadata.toCachedVfsNode(directory.root) }
        }

        val provider = providerResolver(directory.root)
        val providerChildren = provider.listChildren(directory.sourceNode)
        val childMetadata = providerChildren.map { child -> child.metadata }
        directoryListingCache.replaceChildren(directory, childMetadata)
        return providerChildren.map { child ->
            metadataCache.put(directory.root.id, child.metadata)
            child.toVfsNode()
        }
    }

    suspend fun openInputStream(file: VfsNode): InputStream? =
        providerFor(file).openInputStream(file.sourceNode)

    suspend fun openInputStream(root: LibraryRootEntity, path: VfsPath): InputStream? =
        resolve(root, path)?.let { openInputStream(it) }

    suspend fun openInputStream(file: VfsNode, offset: Long): InputStream? =
        providerFor(file).openInputStream(file.sourceNode, offset)

    suspend fun openInputStream(root: LibraryRootEntity, path: VfsPath, offset: Long): InputStream? =
        resolve(root, path)?.let { openInputStream(it, offset) }

    suspend fun readRange(file: VfsNode, offset: Long, length: Int): ByteArray? =
        providerFor(file).readRange(file.sourceNode, offset, length)

    /**
     * Exposes provider capability without leaking provider instances.
     * Lets metadata-only cache decorators decide whether a bounded read can be cached while playback offset streams keep using
     * provider openInputStream paths directly.
     */
    fun supportsRangeRead(file: VfsNode): Boolean =
        providerFor(file).capabilities.supportsRangeRead

    suspend fun readRange(root: LibraryRootEntity, path: VfsPath, offset: Long, length: Int): ByteArray? =
        resolve(root, path)?.let { readRange(it, offset, length) }

    suspend fun openFileDescriptor(file: VfsNode): ParcelFileDescriptor? =
        providerFor(file).openFileDescriptor(file.sourceNode)

    suspend fun openFileDescriptor(root: LibraryRootEntity, path: VfsPath): ParcelFileDescriptor? =
        resolve(root, path)?.let { openFileDescriptor(it) }

    suspend fun exists(node: VfsNode): Boolean =
        providerFor(node).exists(node.sourceNode)

    private fun providerFor(node: VfsNode): LibrarySourceProvider =
        providerResolver(node.root)

    private fun SourceNode.toVfsNode(): VfsNode =
        VfsNode(
            root = root,
            path = VfsPath(metadata.sourcePath),
            metadata = metadata,
            sourceNode = this
        )

    private fun SourceFileMetadata.toCachedVfsNode(root: LibraryRootEntity): VfsNode {
        val cachedSourceNode = SourceNode(root = root, metadata = this)
        return VfsNode(
            root = root,
            path = VfsPath(sourcePath),
            metadata = this,
            sourceNode = cachedSourceNode
        )
    }
}
