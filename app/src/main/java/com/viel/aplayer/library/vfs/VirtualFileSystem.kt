package com.viel.aplayer.library.vfs

import android.os.ParcelFileDescriptor
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.NoOpDirectoryListingCache
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
        // Relocate node using rootId and sourcePath, replacing manual reconstruction of native provider file references in the business layer.
        val provider = providerResolver(root)
        return provider.resolve(root, path.value)?.toVfsNode()
    }

    suspend fun listChildren(directory: VfsNode): List<VfsNode> {
        // Directory Listing Cache Read (Scopes reusable child snapshots to listChildren only)
        // Cached metadata is wrapped back into VfsNode values without provider handles, keeping playback streams, exists(), and range reads on their live provider paths.
        directoryListingCache.getChildren(directory)?.let { cachedChildren ->
            return cachedChildren.map { metadata -> metadata.toCachedVfsNode(directory.root) }
        }

        val provider = providerResolver(directory.root)
        val providerChildren = provider.listChildren(directory.sourceNode)
        val childMetadata = providerChildren.map { child -> child.metadata }
        // Directory Listing Cache Write (Stores only provider-successful direct child metadata)
        // The cache is refreshed after a successful provider listing so scanner retries never consume partial or failed directory states.
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

    // Player random seek accesses the provider via VFS offset API, allowing remote sources to map directly to Range requests.
    suspend fun openInputStream(file: VfsNode, offset: Long): InputStream? =
        providerFor(file).openInputStream(file.sourceNode, offset)

    // Offset streams resolved via root/path align with regular streaming patterns, shielding the playback layer from the underlying source types.
    suspend fun openInputStream(root: LibraryRootEntity, path: VfsPath, offset: Long): InputStream? =
        resolve(root, path)?.let { openInputStream(it, offset) }

    suspend fun readRange(file: VfsNode, offset: Long, length: Int): ByteArray? =
        // Metadata frame parsing requires propagating length to the provider, allowing WebDAV to specify bytes=start-end instead of start-.
        providerFor(file).readRange(file.sourceNode, offset, length)

    /**
     * Supports Range Read (Exposes provider capability without leaking provider instances)
     * Lets metadata-only cache decorators decide whether a bounded read can be cached while playback offset streams keep using
     * provider openInputStream paths directly.
     */
    fun supportsRangeRead(file: VfsNode): Boolean =
        providerFor(file).capabilities.supportsRangeRead

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
        providerResolver(node.root)

    private fun SourceNode.toVfsNode(): VfsNode =
        VfsNode(
            root = root,
            path = VfsPath(metadata.sourcePath),
            metadata = metadata,
            sourceNode = this
        )

    private fun SourceFileMetadata.toCachedVfsNode(root: LibraryRootEntity): VfsNode {
        // Cached Source Node Reconstruction (Rebuilds the minimal VFS node needed for scanner traversal)
        // Provider handles are intentionally omitted because WebDAV directory operations can resolve from root plus sourcePath metadata.
        val cachedSourceNode = SourceNode(root = root, metadata = this)
        return VfsNode(
            root = root,
            path = VfsPath(sourcePath),
            metadata = this,
            sourceNode = cachedSourceNode
        )
    }
}
