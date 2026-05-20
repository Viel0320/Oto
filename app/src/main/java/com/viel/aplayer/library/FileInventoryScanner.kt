package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import com.viel.aplayer.data.entity.LibraryRootEntity

// New scanner: it only snapshots files and leaves import decisions to ImportOrchestrator.
class FileInventoryScanner(private val context: Context) {
    suspend fun scan(roots: List<LibraryRootEntity>): FileInventory = withContext(Dispatchers.IO) {
        val inventories = roots.mapNotNull { root ->
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(root.treeUri)) ?: return@mapNotNull null
            if (!rootDoc.exists()) return@mapNotNull null
            scanRoot(root, rootDoc)
        }
        merge(roots, inventories)
    }

    private suspend fun scanRoot(root: LibraryRootEntity, rootDoc: DocumentFile): FileInventory {
        val cueFiles = mutableListOf<FileRef>()
        val m3u8Files = mutableListOf<FileRef>()
        val audioFiles = mutableListOf<FileRef>()
        val imagesByParent = mutableMapOf<String, MutableList<FileRef>>()

        suspend fun walk(directory: DocumentFile, parentPath: String) {
            directory.listFiles().forEach { file ->
                yield()
                val name = file.name ?: return@forEach
                val relativePath = if (parentPath.isBlank()) name else "$parentPath/$name"
                if (file.isDirectory) {
                    walk(file, relativePath)
                    return@forEach
                }

                val ref = toRef(root.id, file, directory, relativePath)
                when {
                    isCue(name) -> cueFiles.add(ref)
                    isM3u(name) -> m3u8Files.add(ref)
                    isAudio(name) -> audioFiles.add(ref)
                    isImage(name) -> imagesByParent.getOrPut(ref.parentUri) { mutableListOf() }.add(ref)
                }
            }
        }

        walk(rootDoc, "")

        return FileInventory(
            roots = listOf(root),
            cueFiles = cueFiles.sortedByStableFileKey(),
            m3u8Files = m3u8Files.sortedByStableFileKey(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = imagesByParent.mapValues { it.value.sortedByStableFileKey() }
        )
    }

    private fun toRef(rootId: String, file: DocumentFile, parent: DocumentFile, relativePath: String): FileRef =
        FileRef(
            uri = file.uri.toString(),
            rootId = rootId,
            documentId = file.uri.lastPathSegment ?: file.uri.toString(),
            relativePath = relativePath,
            parentDocumentId = parent.uri.lastPathSegment ?: parent.uri.toString(),
            parentUri = parent.uri.toString(),
            displayName = file.name ?: relativePath.substringAfterLast('/'),
            fileSize = file.length(),
            lastModified = file.lastModified(),
            documentFile = file,
            parentDocumentFile = parent
        )

    private fun merge(roots: List<LibraryRootEntity>, inventories: List<FileInventory>): FileInventory =
        FileInventory(
            roots = roots,
            cueFiles = inventories.flatMap { it.cueFiles }.sortedByStableFileKey(),
            m3u8Files = inventories.flatMap { it.m3u8Files }.sortedByStableFileKey(),
            audioFiles = inventories.flatMap { it.audioFiles }.sortedByStableFileKey(),
            imageFilesByParent = inventories.flatMap { it.imageFilesByParent.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, values) -> values.flatten().sortedByStableFileKey() }
        )

    private fun isCue(name: String): Boolean = name.endsWith(".cue", ignoreCase = true)

    private fun isM3u(name: String): Boolean =
        name.endsWith(".m3u8", ignoreCase = true) || name.endsWith(".m3u", ignoreCase = true)

    private fun isAudio(name: String): Boolean {
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { name.endsWith(it, ignoreCase = true) }
    }

    private fun isImage(name: String): Boolean {
        val extensions = listOf(".jpg", ".jpeg", ".png", ".webp")
        return extensions.any { name.endsWith(it, ignoreCase = true) }
    }
}