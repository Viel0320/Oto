package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import com.viel.aplayer.data.entity.LibraryRootEntity
import androidx.core.net.toUri

// New scanner: it only snapshots files and leaves import decisions to ImportOrchestrator.
class FileInventoryScanner(private val context: Context) {
    suspend fun scan(roots: List<LibraryRootEntity>): FileInventory = withContext(Dispatchers.IO) {
        val inventories = roots.mapNotNull { root ->
            val rootDoc = DocumentFile.fromTreeUri(context, root.treeUri.toUri()) ?: return@mapNotNull null
            if (!rootDoc.exists()) return@mapNotNull null
            scanRoot(root, rootDoc)
        }
        merge(roots, inventories)
    }

    // 详尽的中文注释：以目录关闭事件的形式流式输出扫描结果；当前采用后序遍历，确保一个目录发出时其子目录已经全部扫描完毕。
    fun scanDirectories(roots: List<LibraryRootEntity>): Flow<DirectoryInventory> = flow {
        roots.forEach { root ->
            val rootDoc = DocumentFile.fromTreeUri(context, root.treeUri.toUri()) ?: return@forEach
            if (!rootDoc.exists()) return@forEach
            scanRootDirectories(root, rootDoc) { directoryInventory ->
                emit(directoryInventory)
            }
        }
    }.flowOn(Dispatchers.IO)

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

    // 详尽的中文注释：递归扫描单个目录，先关闭子目录再发出当前目录快照，给 ImportScopeBuilder 留出安全的 scope 闭合判断空间。
    private suspend fun scanRootDirectories(
        root: LibraryRootEntity,
        rootDoc: DocumentFile,
        emitDirectory: suspend (DirectoryInventory) -> Unit
    ) {
        suspend fun walk(directory: DocumentFile, relativePath: String) {
            // 详尽的中文注释：这里的计时只覆盖当前目录直接 listFiles 和文件分类，不把子目录导入背压算进父目录 scan.directoryClosed。
            val directDirectoryScanStartedAt = ImportTimingLogger.mark()
            val cueFiles = mutableListOf<FileRef>()
            val m3u8Files = mutableListOf<FileRef>()
            val audioFiles = mutableListOf<FileRef>()
            val imageFiles = mutableListOf<FileRef>()
            val childDirectories = mutableListOf<Pair<DocumentFile, String>>()

            directory.listFiles().forEach { file ->
                yield()
                val name = file.name ?: return@forEach
                val fileRelativePath = if (relativePath.isBlank()) name else "$relativePath/$name"
                if (file.isDirectory) {
                    // 详尽的中文注释：先记录子目录，等当前目录直接文件分类完成并锁定耗时后再递归，避免父目录计时被子 scope 的导入流程拉长。
                    childDirectories.add(file to fileRelativePath)
                    return@forEach
                }

                val ref = toRef(root.id, file, directory, fileRelativePath)
                when {
                    isCue(name) -> cueFiles.add(ref)
                    isM3u(name) -> m3u8Files.add(ref)
                    isAudio(name) -> audioFiles.add(ref)
                    isImage(name) -> imageFiles.add(ref)
                }
            }
            val directDirectoryScanElapsedMs = ImportTimingLogger.elapsedMs(directDirectoryScanStartedAt)

            // 详尽的中文注释：仍保持后序遍历语义，先释放所有子目录 scope，再释放当前目录 scope，保证父目录启发式不会抢走子目录音频。
            childDirectories.forEach { (childDirectory, childRelativePath) ->
                walk(childDirectory, childRelativePath)
            }

            // 详尽的中文注释：当前目录的直接媒体资产全部收集后再发出 DirectoryInventory，避免启发式看到半个目录。
            ImportTimingLogger.logDuration(
                scopeId = "directory:${directory.uri}",
                stage = "scan.directoryClosed",
                elapsedMs = directDirectoryScanElapsedMs,
                detail = "relativePath=${relativePath.ifBlank { "<root>" }} children=${childDirectories.size} cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} image=${imageFiles.size}"
            )
            emitDirectory(
                DirectoryInventory(
                    root = root,
                    directoryUri = directory.uri.toString(),
                    directoryDocumentId = directory.uri.lastPathSegment ?: directory.uri.toString(),
                    relativePath = relativePath,
                    cueFiles = cueFiles.sortedByStableFileKey(),
                    m3u8Files = m3u8Files.sortedByStableFileKey(),
                    audioFiles = audioFiles.sortedByStableFileKey(),
                    imageFiles = imageFiles.sortedByStableFileKey(),
                    // 为每一次改动添加详尽的中文注释：提取当前正在遍历的物理文件夹的最新修改时间，作为增量判断的核心数据源
                    lastModified = directory.lastModified()
                )
            )
        }

        walk(rootDoc, "")
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
