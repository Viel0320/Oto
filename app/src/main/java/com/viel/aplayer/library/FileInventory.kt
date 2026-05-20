package com.viel.aplayer.library

import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.data.entity.LibraryRootEntity

// Scanner output: a stable file inventory, not import decisions.
data class FileInventory(
    val roots: List<LibraryRootEntity>,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFilesByParent: Map<String, List<FileRef>>
) {
    // Cold-start light scans must only feed previously unseen files into import parsing.
    fun onlyUnclaimed(existingClaimIndex: ExistingClaimIndex): FileInventory =
        FileInventory(
            roots = roots,
            cueFiles = cueFiles.filterNot { existingClaimIndex.has(it.identity) },
            m3u8Files = m3u8Files.filterNot { existingClaimIndex.has(it.identity) },
            audioFiles = audioFiles.filterNot { existingClaimIndex.has(it.identity) },
            imageFilesByParent = imageFilesByParent
        )

    /**
     * 为每一次改动添加详尽的中文注释：
     * 将当前的全局文件快照 FileInventory 按照物理同级目录（即以 parentUri 为依据）进行分包拆分。
     * 每个分包所得的 FileInventory 实例仅包含该同级物理目录下的清单文件、音频文件、图片资产，
     * 以及该目录所绑定的库根实体（LibraryRootEntity）。
     * 
     * @return 拆分后的同级目录文件子包列表
     */
    fun groupByParent(): List<FileInventory> {
        // 1. 去重收集所有文件资产（包含清单、音频和同级图片）涉及的物理父目录 Uri
        val allParentUris = buildSet {
            cueFiles.forEach { add(it.parentUri) }
            m3u8Files.forEach { add(it.parentUri) }
            audioFiles.forEach { add(it.parentUri) }
            addAll(imageFilesByParent.keys)
        }

        // 2. 预先按照 parentUri 对各种资产进行内存分组，避免在循环中重复过滤以提升性能
        val cueByParent = cueFiles.groupBy { it.parentUri }
        val m3u8ByParent = m3u8Files.groupBy { it.parentUri }
        val audioByParent = audioFiles.groupBy { it.parentUri }

        // 3. 针对每一个物理同级目录，组装出纯净的子 FileInventory 实例
        return allParentUris.map { parentUri ->
            val parentCues = cueByParent[parentUri].orEmpty()
            val parentM3u8s = m3u8ByParent[parentUri].orEmpty()
            val parentAudios = audioByParent[parentUri].orEmpty()
            val parentImages = imageFilesByParent[parentUri].orEmpty()

            // 4. 智能提取本子目录下资产所关联的 rootId，过滤出对应的库根实体
            val involvedRootIds = buildSet {
                parentCues.forEach { add(it.rootId) }
                parentM3u8s.forEach { add(it.rootId) }
                parentAudios.forEach { add(it.rootId) }
                parentImages.forEach { add(it.rootId) }
            }
            val parentRoots = roots.filter { it.id in involvedRootIds }.ifEmpty { roots }

            FileInventory(
                roots = parentRoots,
                cueFiles = parentCues,
                m3u8Files = parentM3u8s,
                audioFiles = parentAudios,
                imageFilesByParent = if (parentImages.isNotEmpty()) mapOf(parentUri to parentImages) else emptyMap()
            )
        }
    }
}

// 详尽的中文注释：DirectoryInventory 表示扫描器已经关闭的单个物理目录快照，是后续 scope 级流式导入的输入事件。
data class DirectoryInventory(
    val root: LibraryRootEntity,
    val directoryUri: String,
    val directoryDocumentId: String,
    val relativePath: String,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFiles: List<FileRef>
) {
    // 详尽的中文注释：保留 root 目录识别能力，供后续需要按授权根统计或切换释放策略时复用。
    val isRootDirectory: Boolean get() = relativePath.isBlank()

    // 详尽的中文注释：冷启动轻量扫描沿用旧逻辑，只让未被 BookFile 认领过的清单与音频进入后续 scope 构建。
    fun onlyUnclaimed(existingClaimIndex: ExistingClaimIndex): DirectoryInventory =
        copy(
            cueFiles = cueFiles.filterNot { existingClaimIndex.has(it.identity) },
            m3u8Files = m3u8Files.filterNot { existingClaimIndex.has(it.identity) },
            audioFiles = audioFiles.filterNot { existingClaimIndex.has(it.identity) }
        )
}

// Runtime file reference used during a single scan/import run.
data class FileRef(
    val uri: String,
    val rootId: String,
    val documentId: String,
    val relativePath: String,
    val parentDocumentId: String,
    val parentUri: String,
    val displayName: String,
    val fileSize: Long,
    val lastModified: Long,
    // Keep DocumentFile out of Room models; it is only used by parsers/resolvers during this run.
    val documentFile: DocumentFile,
    // Manifest parsers need the direct parent folder to resolve relative entries.
    val parentDocumentFile: DocumentFile
) {
    val identity: FileIdentity = FileIdentity(uri = uri, documentId = documentId)
}

// File ownership identity: uri is first-version primary, documentId is a stable helper when present.
data class FileIdentity(
    val uri: String,
    val documentId: String
) {
    fun keys(): Set<String> = buildSet {
        add("uri:$uri")
        if (documentId.isNotBlank()) add("doc:$documentId")
    }
}

// Stable scanner ordering keeps the same import result for the same file tree.
fun Iterable<FileRef>.sortedByStableFileKey(): List<FileRef> =
    sortedWith(compareBy<FileRef> { it.relativePath.lowercase() }.thenBy { it.documentId }.thenBy { it.uri })
