package com.viel.aplayer.library

import com.viel.aplayer.data.entity.LibraryRootEntity

// Scanner output: a stable file inventory, not import decisions.
data class FileInventory(
    val roots: List<LibraryRootEntity>,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFilesByParent: Map<String, List<FileRef>>,
    // 为每一次改动添加详尽的中文注释：把同目录 txt 侧车文件也纳入扫描快照，
    // 这样 manifest parser 可以直接消费本轮目录快照完成简介匹配，
    // 不必在后续步骤里再次通过 VFS 重新枚举目录。
    val textFilesByParent: Map<String, List<FileRef>>
) {
    // Cold-start light scans must only feed previously unseen files into import parsing.
    fun onlyUnclaimed(existingClaimIndex: ExistingClaimIndex): FileInventory =
        FileInventory(
            roots = roots,
            cueFiles = cueFiles.filterNot { existingClaimIndex.has(it.identity) },
            m3u8Files = m3u8Files.filterNot { existingClaimIndex.has(it.identity) },
            audioFiles = audioFiles.filterNot { existingClaimIndex.has(it.identity) },
            imageFilesByParent = imageFilesByParent,
            // 为每一次改动添加详尽的中文注释：txt 侧车不参与 claim 过滤，
            // 它们只是目录级辅助资产，因此在 onlyUnclaimed 里保持原样透传。
            textFilesByParent = textFilesByParent
        )

    /**
     * 为每一次改动添加详尽的中文注释：
     * 将当前的全局文件快照 FileInventory 按照 VFS 同级目录（即以 parentSourceKey 为依据）进行分包拆分。
     * 每个分包所得的 FileInventory 实例仅包含该同级物理目录下的清单文件、音频文件、图片资产，
     * 以及该目录所绑定的库根实体（LibraryRootEntity）。
     * 
     * @return 拆分后的同级目录文件子包列表
     */
    fun groupByParent(): List<FileInventory> {
        // 为每一次改动添加详尽的中文注释：按 VFS 父目录键拆分目录 scope，避免导入分包继续依赖 SAF 父目录 Uri。
        val allParentKeys = buildSet {
            cueFiles.forEach { add(it.parentSourceKey) }
            m3u8Files.forEach { add(it.parentSourceKey) }
            audioFiles.forEach { add(it.parentSourceKey) }
            addAll(imageFilesByParent.keys)
            // 为每一次改动添加详尽的中文注释：按父目录拆分时把 txt 目录键也计入，
            // 避免“只有 manifest + txt，没有图片”的目录在分包时丢失描述侧车上下文。
            addAll(textFilesByParent.keys)
        }

        // 为每一次改动添加详尽的中文注释：分组索引使用 rootId/sourcePath 组合，后续远程来源也能稳定命中。
        val cueByParent = cueFiles.groupBy { it.parentSourceKey }
        val m3u8ByParent = m3u8Files.groupBy { it.parentSourceKey }
        val audioByParent = audioFiles.groupBy { it.parentSourceKey }

        // 3. 针对每一个物理同级目录，组装出纯净的子 FileInventory 实例
        return allParentKeys.map { parentKey ->
            val parentCues = cueByParent[parentKey].orEmpty()
            val parentM3u8s = m3u8ByParent[parentKey].orEmpty()
            val parentAudios = audioByParent[parentKey].orEmpty()
            val parentImages = imageFilesByParent[parentKey].orEmpty()
            val parentTexts = textFilesByParent[parentKey].orEmpty()

            // 4. 智能提取本子目录下资产所关联的 rootId，过滤出对应的库根实体
            val involvedRootIds = buildSet {
                parentCues.forEach { add(it.rootId) }
                parentM3u8s.forEach { add(it.rootId) }
                parentAudios.forEach { add(it.rootId) }
                parentImages.forEach { add(it.rootId) }
                // 为每一次改动添加详尽的中文注释：txt 文件也从属某个 root，
                // 这里同步纳入根过滤，保证拆出的子包仍能正确初始化 VFS reader。
                parentTexts.forEach { add(it.rootId) }
            }
            val parentRoots = roots.filter { it.id in involvedRootIds }.ifEmpty { roots }

            FileInventory(
                roots = parentRoots,
                cueFiles = parentCues,
                m3u8Files = parentM3u8s,
                audioFiles = parentAudios,
                imageFilesByParent = if (parentImages.isNotEmpty()) mapOf(parentKey to parentImages) else emptyMap(),
                // 为每一次改动添加详尽的中文注释：与图片侧车一样，txt 侧车按父目录随子包一起下发，
                // 供 manifest parser 在 scope 内部直接完成简介补全。
                textFilesByParent = if (parentTexts.isNotEmpty()) mapOf(parentKey to parentTexts) else emptyMap()
            )
        }
    }
}

// 详尽的中文注释：DirectoryInventory 表示扫描器已经关闭的单个物理目录快照，是后续 scope 级流式导入的输入事件。
data class DirectoryInventory(
    val root: LibraryRootEntity,
    // 为每一次改动添加详尽的中文注释：目录快照只保存 VFS sourcePath，目录缓存和增量导入不再持久依赖 SAF URI。
    val sourcePath: String,
    val sourceIdentity: String,
    val cueFiles: List<FileRef>,
    val m3u8Files: List<FileRef>,
    val audioFiles: List<FileRef>,
    val imageFiles: List<FileRef>,
    // 为每一次改动添加详尽的中文注释：目录关闭事件额外携带同级 txt 文件，
    // 让后续 manifest scope 在不重新 listChildren 的前提下就能拿到简介侧车候选。
    val textFiles: List<FileRef>,
    // 为每一次改动添加详尽的中文注释：新增物理文件夹最后修改时间属性，默认赋予 0L 以确保最大化的反射及兼容安全性
    val lastModified: Long = 0L
) {
    // 详尽的中文注释：保留 root 目录识别能力，供后续需要按授权根统计或切换释放策略时复用。
    val isRootDirectory: Boolean get() = sourcePath.isBlank()

    // 详尽的中文注释：冷启动轻量扫描沿用旧逻辑，只让未被 BookFile 认领过的清单与音频进入后续 scope 构建。
    fun onlyUnclaimed(existingClaimIndex: ExistingClaimIndex): DirectoryInventory =
        copy(
            cueFiles = cueFiles.filterNot { existingClaimIndex.has(it.identity) },
            m3u8Files = m3u8Files.filterNot { existingClaimIndex.has(it.identity) },
            audioFiles = audioFiles.filterNot { existingClaimIndex.has(it.identity) }
            // 为每一次改动添加详尽的中文注释：txt 与图片一样不参与“已认领文件”过滤，
            // 它们只作为仍需保留的目录辅助资产存在。
        )
}

// Runtime file reference used during a single scan/import run.
data class FileRef(
    val rootId: String,
    // 为每一次改动添加详尽的中文注释：sourcePath 是扫描期文件定位字段，后续所有解析都通过 VFS 使用它。
    val sourcePath: String,
    // 为每一次改动添加详尽的中文注释：sourceIdentity 是跨协议身份键，不再保存 SAF 专属旧身份作为运行时字段。
    val sourceIdentity: String,
    // etag 是远程增量检测预留字段；SAF 没有稳定 etag 时保持 null。
    val etag: String? = null,
    // 为每一次改动添加详尽的中文注释：parentSourcePath 和 parentSourceKey 用于同目录匹配、claim 限定和 sidecar 查找。
    val parentSourcePath: String,
    val parentSourceKey: String,
    val parentSourceIdentity: String,
    val displayName: String,
    val fileSize: Long,
    val lastModified: Long
) {
    // 为每一次改动添加详尽的中文注释：扫描期 FileRef 的稳定键直接等于 VFS 路径键，后续 manifest、claim 和日志都不再回退到 provider URI。
    val vfsKey: String = vfsFileKey(rootId, sourcePath)

    // 为每一次改动添加详尽的中文注释：所有权身份使用 rootId/sourcePath 为主、sourceIdentity 为辅，完全替代旧 uri 身份。
    val identity: FileIdentity = FileIdentity(rootId = rootId, sourcePath = sourcePath, sourceIdentity = sourceIdentity)
}

// 为每一次改动添加详尽的中文注释：FileIdentity 只描述 VFS 标准身份，不再把 provider URI 放进 claim key。
data class FileIdentity(
    val rootId: String,
    val sourcePath: String,
    val sourceIdentity: String
) {
    fun keys(): Set<String> = buildSet {
        add("path:${vfsFileKey(rootId, sourcePath)}")
        if (sourceIdentity.isNotBlank()) add("src:$rootId:$sourceIdentity")
    }
}

// 为每一次改动添加详尽的中文注释：VFS 文件键使用不可见分隔符组合 rootId/sourcePath，避免普通路径字符导致键碰撞。
fun vfsFileKey(rootId: String, sourcePath: String): String = "$rootId\u001F$sourcePath"

// Stable scanner ordering keeps the same import result for the same file tree.
fun Iterable<FileRef>.sortedByStableFileKey(): List<FileRef> =
    sortedWith(compareBy<FileRef> { it.sourcePath.lowercase() }.thenBy { it.sourceIdentity }.thenBy { it.vfsKey })
