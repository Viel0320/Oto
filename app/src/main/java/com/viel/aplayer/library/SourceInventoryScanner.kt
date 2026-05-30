package com.viel.aplayer.library

import android.content.Context
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.library.vfs.VirtualFileSystem
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.logger.ImportTimingLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

// 新扫描器只面向 VFS 工作；第一阶段由 SAF Provider 提供数据，后续 WebDAV Provider 可复用同一条导入流水线。
class SourceInventoryScanner(context: Context) {
    private val vfs = VirtualFileSystem(LibrarySourceProviderFactory(context.applicationContext))

    suspend fun scan(roots: List<LibraryRootEntity>): FileInventory = withContext(Dispatchers.IO) {
        val inventories = roots.mapNotNull { root ->
            val rootNode = vfs.root(root) ?: return@mapNotNull null
            if (!vfs.exists(rootNode)) return@mapNotNull null
            scanRoot(root, rootNode)
        }
        merge(roots, inventories)
    }

    // 保持现有“目录关闭后释放 DirectoryInventory”的后序遍历语义，确保导入即时入库边界不变。
    fun scanDirectories(roots: List<LibraryRootEntity>): Flow<DirectoryInventory> = flow {
        roots.forEach { root ->
            val rootNode = vfs.root(root) ?: return@forEach
            if (!vfs.exists(rootNode)) return@forEach
            scanRootDirectories(root, rootNode) { directoryInventory ->
                emit(directoryInventory)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun scanRoot(root: LibraryRootEntity, rootNode: VfsNode): FileInventory {
        val cueFiles = mutableListOf<FileRef>()
        val m3u8Files = mutableListOf<FileRef>()
        val audioFiles = mutableListOf<FileRef>()
        val imagesByParent = mutableMapOf<String, MutableList<FileRef>>()
        // 全量扫描同时为 txt 侧车建立按父目录分组的快照，
        // 让后续 manifest parser 可以直接复用扫描结果完成简介匹配。
        val textFilesByParent = mutableMapOf<String, MutableList<FileRef>>()

        suspend fun walk(directory: VfsNode) {
            vfs.listChildren(directory).forEach { node ->
                yield()
                val name = node.metadata.displayName
                if (node.metadata.isDirectory) {
                    walk(node)
                    return@forEach
                }

                val ref = toRef(root.id, node, directory)
                when {
                    isCue(name) -> cueFiles.add(ref)
                    isM3u(name) -> m3u8Files.add(ref)
                    isAudio(name) -> audioFiles.add(ref)
                    isImage(name) -> imagesByParent.getOrPut(ref.parentSourceKey) { mutableListOf() }.add(ref)
                    // txt 不进入 claim 主体，只作为目录侧车资产保留。
                    isText(name) -> textFilesByParent.getOrPut(ref.parentSourceKey) { mutableListOf() }.add(ref)
                }
            }
        }

        walk(rootNode)

        return FileInventory(
            roots = listOf(root),
            cueFiles = cueFiles.sortedByStableFileKey(),
            m3u8Files = m3u8Files.sortedByStableFileKey(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = imagesByParent.mapValues { it.value.sortedByStableFileKey() },
            textFilesByParent = textFilesByParent.mapValues { it.value.sortedByStableFileKey() }
        )
    }

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
            // 目录关闭事件保留同级 txt 资产，
            // 后续 manifest scope 可以直接在当前目录快照中匹配简介文件。
            val textFiles = mutableListOf<FileRef>()
            val childDirectories = mutableListOf<VfsNode>()

            vfs.listChildren(directory).forEach { node ->
                yield()
                val name = node.metadata.displayName
                if (node.metadata.isDirectory) {
                    // 先记录子目录，等当前目录直接文件分类完成后再递归，保持旧扫描器的后序释放顺序。
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

    private fun merge(roots: List<LibraryRootEntity>, inventories: List<FileInventory>): FileInventory =
        FileInventory(
            roots = roots,
            cueFiles = inventories.flatMap { it.cueFiles }.sortedByStableFileKey(),
            m3u8Files = inventories.flatMap { it.m3u8Files }.sortedByStableFileKey(),
            audioFiles = inventories.flatMap { it.audioFiles }.sortedByStableFileKey(),
            imageFilesByParent = inventories.flatMap { it.imageFilesByParent.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, values) -> values.flatten().sortedByStableFileKey() },
            textFilesByParent = inventories.flatMap { it.textFilesByParent.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, values) -> values.flatten().sortedByStableFileKey() }
        )

    private fun isCue(name: String): Boolean = name.endsWith(".cue", ignoreCase = true)

    private fun isM3u(name: String): Boolean =
        name.endsWith(".m3u8", ignoreCase = true) || name.endsWith(".m3u", ignoreCase = true)

    private fun isAudio(name: String): Boolean {
        // mp4 也可能承载纯音频/有声书章节，扫描阶段统一归入 audioFiles 交给元数据解析器裁决。
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".mp4", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { name.endsWith(it, ignoreCase = true) }
    }

    private fun isImage(name: String): Boolean {
        val extensions = listOf(".jpg", ".jpeg", ".png", ".webp")
        return extensions.any { name.endsWith(it, ignoreCase = true) }
    }

    private fun isText(name: String): Boolean {
        // 当前只把 txt 视为简介侧车来源，
        // 保持与既有 ConflictClaimStep 中的描述匹配规则一致，不顺手放大到 md/nfo 等其他文本格式。
        return name.endsWith(".txt", ignoreCase = true)
    }
}
