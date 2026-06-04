package com.viel.aplayer.library.vfs

import android.os.ParcelFileDescriptor
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProvider
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import java.io.InputStream

// VfsPath provides a unified abstraction for cross-protocol paths; both SAF and WebDAV use it to describe internal paths.
data class VfsPath(val value: String) {
    val isRoot: Boolean get() = value.isBlank()
}

// VfsNode is the standard node shared among scanning, caching, and reading flows, removing raw provider file references from the business layer.
data class VfsNode(
    val root: LibraryRootEntity,
    val path: VfsPath,
    val metadata: SourceFileMetadata,
    val sourceNode: SourceNode
)

// VFS caching strategy; uses NO_CACHE initially, and WebDAV can support directory metadata and small file caching in later phases.
enum class VfsCachePolicy {
    NO_CACHE,
    METADATA_ONLY,
    SMALL_FILE
}

// Metadata cache interface defining boundary; uses no-op for SAF initially, WebDAV implements Room/disk cache later.
interface VfsMetadataCache {
    suspend fun get(rootId: String, path: VfsPath): SourceFileMetadata?
    suspend fun put(rootId: String, metadata: SourceFileMetadata)
}

// No-op cache implementation preserves the real-time characteristics and legacy behavior of SAF scanning.
object NoOpVfsMetadataCache : VfsMetadataCache {
    override suspend fun get(rootId: String, path: VfsPath): SourceFileMetadata? = null
    override suspend fun put(rootId: String, metadata: SourceFileMetadata) = Unit
}

// VirtualFileSystem encapsulates provider access; scanner components depend on VFS instead of coupling to SAF or WebDAV directly.
class VirtualFileSystem(
    private val providerFactory: LibrarySourceProviderFactory,
    private val metadataCache: VfsMetadataCache = NoOpVfsMetadataCache
) {
    suspend fun root(root: LibraryRootEntity): VfsNode? {
        val provider = providerFactory.providerFor(root)
        return provider.rootDirectory(root)?.toVfsNode()
    }

    suspend fun resolve(root: LibraryRootEntity, path: VfsPath): VfsNode? {
        // Relocate node using rootId and sourcePath, replacing manual reconstruction of native provider file references in the business layer.
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

    // Player random seek accesses the provider via VFS offset API, allowing remote sources to map directly to Range requests.
    suspend fun openInputStream(file: VfsNode, offset: Long): InputStream? =
        providerFor(file).openInputStream(file.sourceNode, offset)

    // Offset streams resolved via root/path align with regular streaming patterns, shielding the playback layer from the underlying source types.
    suspend fun openInputStream(root: LibraryRootEntity, path: VfsPath, offset: Long): InputStream? =
        resolve(root, path)?.let { openInputStream(it, offset) }

    suspend fun readRange(file: VfsNode, offset: Long, length: Int): ByteArray? =
        // Metadata frame parsing requires propagating length to the provider, allowing WebDAV to specify bytes=start-end instead of start-.
        providerFor(file).readRange(file.sourceNode, offset, length)

    suspend fun readRange(root: LibraryRootEntity, path: VfsPath, offset: Long, length: Int): ByteArray? =
        // Performs bounded range reads on root/path to prevent metadata reading from consuming open-ended offset playback streams.
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
