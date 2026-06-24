package com.viel.oto.library.vfs.cache

import com.viel.oto.library.vfs.VfsNode
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata

/**
 * Scanner-facing cache abstraction for direct child snapshots.
 * Keeps directory listing reuse isolated from playback, availability checks, and range reads by exposing only VfsNode
 * directory inputs plus SourceFileMetadata child snapshots.
 */
interface DirectoryListingCache {
    suspend fun getChildren(directory: VfsNode): List<SourceFileMetadata>?
    suspend fun replaceChildren(directory: VfsNode, children: List<SourceFileMetadata>)
    suspend fun evictRoot(rootId: String)
}

/**
 * Default VFS behavior for non-scanner callers.
 * Preserves provider-direct reads unless a scanner explicitly injects a Room-backed cache instance.
 */
object NoOpDirectoryListingCache : DirectoryListingCache {
    override suspend fun getChildren(directory: VfsNode): List<SourceFileMetadata>? = null
    override suspend fun replaceChildren(directory: VfsNode, children: List<SourceFileMetadata>) = Unit
    override suspend fun evictRoot(rootId: String) = Unit
}
