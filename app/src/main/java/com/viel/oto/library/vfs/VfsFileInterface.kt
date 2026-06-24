package com.viel.oto.library.vfs

import android.content.Context
import android.os.SystemClock
import com.viel.oto.data.cache.OnlineSourceCachePolicy
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.FileRef
import com.viel.oto.library.vfs.cache.CachedRangeReader
import com.viel.oto.library.vfs.cache.VfsRangeCache
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.oto.library.vfs.sourceProvider.SourceNode
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Narrows Media3 data-source access to offset stream acquisition.
 * Keeps playback callers dependent on a single VFS read capability instead of the full directory, range-cache, and descriptor facade.
 */
interface VfsPlaybackStreamReader {
    suspend fun openPlaybackStream(file: BookFileEntity, offset: Long): InputStream?
}

/**
 * Read-only VFS facade.
 *
 * Responsibilities:
 * 1. Locates files by `rootId + sourcePath`.
 * 2. Provides `open / listChildren / readRange` actions.
 * 3. Prefers direct range node construction to bypass redundant `resolve()` overhead.
 *
 * Decouples layout complexities by removing helpers for audio tags, chapters, or covers.
 */
class VfsFileInterface(
    context: Context,
    private val libraryRootDao: LibraryRootDao? = null,
    private val rootsById: Map<String, LibraryRootEntity> = emptyMap(),
    rangeCache: VfsRangeCache? = null
) : VfsPlaybackStreamReader {
    private val vfs = VirtualFileSystem(LibrarySourceProviderFactory(context.applicationContext))
    private val cachedRangeReader = rangeCache?.let { cache ->
        CachedRangeReader(
            rangeCache = cache,
            supportsRangeRead = { node -> vfs.supportsRangeRead(node) },
            readRange = { node, offset, length -> vfs.readRange(node, offset, length) }
        )
    }
    /**
     * Root Cache Snapshot: Wraps a LibraryRootEntity with its creation timestamp.
     * This supports time-to-live (TTL) cache eviction behavior.
     */
    private data class CachedRoot(
        val entity: LibraryRootEntity,
        val cachedAtElapsedMs: Long
    )

    private val rootCache = ConcurrentHashMap<String, CachedRoot>()

    suspend fun open(file: FileRef): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node)
    }

    suspend fun open(file: FileRef, offset: Long): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node, offset)
    }

    suspend fun open(file: BookFileEntity): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node)
    }

    suspend fun open(file: BookFileEntity, offset: Long): InputStream? {
        val root = rootFor(file.rootId) ?: return null
        val node = directOpenNode(root, file)
        return vfs.openInputStream(node, offset)
    }

    override suspend fun openPlaybackStream(file: BookFileEntity, offset: Long): InputStream? =
        open(file, offset)

    suspend fun open(node: VfsNode): InputStream? =
        vfs.openInputStream(node)

    suspend fun listChildren(rootId: String, sourcePath: String): List<VfsNode> {
        val root = rootFor(rootId) ?: return emptyList()
        val directory = vfs.resolve(root, VfsPath(sourcePath)) ?: return emptyList()
        return vfs.listChildren(directory)
    }

    suspend fun readRange(file: FileRef, offset: Long, length: Int): ByteArray? {
        val root = rootFor(file.rootId) ?: return null
        val node = directRangeNode(root, file)
        return cachedRangeReader?.read(node, offset, length) ?: vfs.readRange(node, offset, length)
    }

    suspend fun readRange(file: BookFileEntity, offset: Long, length: Int): ByteArray? {
        val root = rootFor(file.rootId) ?: return null
        val node = directRangeNode(root, file)
        return cachedRangeReader?.read(node, offset, length) ?: vfs.readRange(node, offset, length)
    }

    private fun directOpenNode(root: LibraryRootEntity, file: FileRef): VfsNode =
        directRangeNode(root, file)

    private fun directOpenNode(root: LibraryRootEntity, file: BookFileEntity): VfsNode =
        directRangeNode(root, file)

    private fun directRangeNode(root: LibraryRootEntity, file: FileRef): VfsNode {
        val metadata = SourceFileMetadata(
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

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val cached = rootCache[rootId]
        if (cached != null && OnlineSourceCachePolicy.isFresh(cached.cachedAtElapsedMs, nowElapsedMs, OnlineSourceCachePolicy.LIBRARY_ROOT_CACHE_TTL_MS)) {
            return cached.entity
        }

        return libraryRootDao?.getRootById(rootId)?.also { root ->
            rootCache[rootId] = CachedRoot(root, nowElapsedMs)
        }
    }

    /**
     * Evict Root Cache: Removes the cached LibraryRootEntity from in-memory cache.
     * This ensures that subsequent file operations resolve the updated endpoint coordinates or credentials from database.
     */
    fun evictRoot(rootId: String) {
        rootCache.remove(rootId)
    }
}
