package com.viel.aplayer.library

import android.content.Context
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.library.vfs.VirtualFileSystem
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.NoOpDirectoryListingCache
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.logger.ImportTimingLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield

// VFS-aligned Scanner (SAF Provider supplies data in Phase 1; WebDavProvider shares the same pipeline in later phases)
class SourceInventoryScanner(
    context: Context,
    directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache
) {
    private val vfs = VirtualFileSystem(
        providerFactory = LibrarySourceProviderFactory(context.applicationContext),
        directoryListingCache = directoryListingCache
    )

    // Post-order Traversal (Releases DirectoryInventory when folders close to guarantee stable transactional boundaries)
    // Single Scanner Entry Point (Keeps file classification rules in the directory-streaming path only)
    // The removed aggregate scan entry had no production callers and duplicated file-type branching, so scanner consumers now use this streaming interface.
    fun scanDirectories(roots: List<LibraryRootEntity>): Flow<DirectoryInventory> = flow {
        roots.forEach { root ->
            val rootNode = vfs.root(root) ?: return@forEach
            if (!vfs.exists(rootNode)) return@forEach
            scanRootDirectories(root, rootNode) { directoryInventory ->
                emit(directoryInventory)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun scanRootDirectories(
        root: LibraryRootEntity,
        rootNode: VfsNode,
        emitDirectory: suspend (DirectoryInventory) -> Unit
    ) {
        suspend fun walk(directory: VfsNode) {
            val directDirectoryScanStartedAt = ImportTimingLogger.mark()
            val cueFiles = mutableListOf<FileRef>()
            val m3u8Files = mutableListOf<FileRef>()
            val audioFiles = mutableListOf<FileRef>()
            val imageFiles = mutableListOf<FileRef>()
            // Retains sibling text files when emitting directory closure, allowing manifest scopes to resolve synopsis files.
            val textFiles = mutableListOf<FileRef>()
            val childDirectories = mutableListOf<VfsNode>()

            vfs.listChildren(directory).forEach { node ->
                yield()
                val name = node.metadata.displayName
                if (node.metadata.isDirectory) {
                    // Post-order Queue (Enlists folders first and walks recursively after file classification completes)
                    childDirectories.add(node)
                    return@forEach
                }

                val ref = toRef(root.id, node, directory)
                when {
                    isCue(name) -> cueFiles.add(ref)
                    isM3u(name) -> m3u8Files.add(ref)
                    isAudio(name) -> audioFiles.add(ref)
                    isImage(name) -> imageFiles.add(ref)
                    isText(name) -> textFiles.add(ref)
                }
            }
            val directDirectoryScanElapsedMs = ImportTimingLogger.elapsedMs(directDirectoryScanStartedAt)

            childDirectories.forEach { childDirectory ->
                walk(childDirectory)
            }

            ImportTimingLogger.logDuration(
                scopeId = "directory:${directory.root.id}:${directory.metadata.sourcePath}",
                stage = "scan.directoryClosed",
                elapsedMs = directDirectoryScanElapsedMs,
                detail = "sourcePath=${directory.metadata.sourcePath.ifBlank { "<root>" }} children=${childDirectories.size} cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} image=${imageFiles.size} txt=${textFiles.size}"
            )
            emitDirectory(
                DirectoryInventory(
                    root = root,
                    sourcePath = directory.metadata.sourcePath,
                    sourceIdentity = directory.metadata.identity,
                    cueFiles = cueFiles.sortedByStableFileKey(),
                    m3u8Files = m3u8Files.sortedByStableFileKey(),
                    audioFiles = audioFiles.sortedByStableFileKey(),
                    imageFiles = imageFiles.sortedByStableFileKey(),
                    textFiles = textFiles.sortedByStableFileKey(),
                    lastModified = directory.metadata.lastModified
                )
            )
        }

        walk(rootNode)
    }

    private fun toRef(rootId: String, file: VfsNode, parent: VfsNode): FileRef =
        FileRef(
            rootId = rootId,
            sourcePath = file.metadata.sourcePath,
            sourceIdentity = file.metadata.identity,
            etag = file.metadata.etag,
            parentSourcePath = parent.metadata.sourcePath,
            parentSourceKey = "${parent.root.id}:${parent.metadata.sourcePath}",
            parentSourceIdentity = parent.metadata.identity,
            displayName = file.metadata.displayName,
            fileSize = file.metadata.fileSize,
            lastModified = file.metadata.lastModified
        )

    private fun isCue(name: String): Boolean = name.endsWith(".cue", ignoreCase = true)

    private fun isM3u(name: String): Boolean =
        name.endsWith(".m3u8", ignoreCase = true) || name.endsWith(".m3u", ignoreCase = true)

    private fun isAudio(name: String): Boolean {
        // MP4 containers are mapped to audioFiles to let the metadata resolvers determine audio capabilities.
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".mp4", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { name.endsWith(it, ignoreCase = true) }
    }

    private fun isImage(name: String): Boolean {
        val extensions = listOf(".jpg", ".jpeg", ".png", ".webp")
        return extensions.any { name.endsWith(it, ignoreCase = true) }
    }

    private fun isText(name: String): Boolean {
        // Limits description sidecars to .txt files to align with matching rules inside ConflictClaimStep.
        return name.endsWith(".txt", ignoreCase = true)
    }
}
