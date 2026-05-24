package com.viel.aplayer.library

import com.viel.aplayer.data.entity.BookFileEntity

// Derived from BookFile rows; this is the only persisted source of file ownership.
class ExistingClaimIndex private constructor(
    private val byKey: Map<String, BookFileEntity>
) {
    /**
     * 为每一次改动添加详尽的中文注释：
     * 根据物理资产身份在已入库索引中检索是否存在冲突所有权。
     * 当传入可选的 currentParentSourcePath 时，检索将自动被限缩在同一个 VFS 文件夹（同级目录）下，
     * 只有当已占用的物理文件的父目录与当前被认领文件的父目录完全相同时，才判定为已被抢占，
     * 跨物理文件夹的同名音频不视为抢占冲突，完美实现局域化抢占。
     * 
     * @param identity 被认领文件的物理身份
     * @param currentParentSourcePath 当前正在被认领的文件所属的 VFS 父目录路径
     * @return 产生抢占冲突的已有 BookFileEntity，无冲突则返回 null
     */
    fun find(identity: FileIdentity, currentParentSourcePath: String? = null): BookFileEntity? {
        val found = identity.keys().firstNotNullOfOrNull { byKey[it] } ?: return null
        if (currentParentSourcePath == null) return found
        
        // 为每一次改动添加详尽的中文注释：从已入库 sourcePath 计算父目录，claim 冲突只在同一个 VFS 父路径内成立。
        val foundParent = found.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        
        return if (foundParent.equals(currentParentSourcePath, ignoreCase = true)) {
            found
        } else {
            null
        }
    }

    fun has(identity: FileIdentity, currentParentSourcePath: String? = null): Boolean =
        find(identity, currentParentSourcePath) != null

    // A manifest rescan is a no-op only when every claimed file already belongs to one book.
    fun completeExistingClaim(identities: List<FileIdentity>): ExistingBookClaim? {
        if (identities.isEmpty()) return null

        val matchesByIdentity = identities.map { identity ->
            identity.keys().mapNotNull { key -> byKey[key] }.distinctBy { it.id }
        }
        if (matchesByIdentity.any { it.isEmpty() }) return null

        val matchedFiles = matchesByIdentity.flatten().distinctBy { it.id }
        val bookIds = matchedFiles.map { it.bookId }.distinct()
        return if (bookIds.size == 1) {
            ExistingBookClaim(bookId = bookIds.first(), files = matchedFiles)
        } else {
            null
        }
    }

    companion object {
        fun from(files: List<BookFileEntity>): ExistingClaimIndex {
            val map = mutableMapOf<String, BookFileEntity>()
            files.forEach { file ->
                // 为每一次改动添加详尽的中文注释：已入库文件索引只使用 rootId/sourcePath 与 sourceIdentity，不再从旧 uri 列回填 claim key。
                val identity = FileIdentity(file.rootId, file.sourcePath, file.sourceIdentity)
                identity.keys().forEach { key -> map.putIfAbsent(key, file) }
            }
            return ExistingClaimIndex(map)
        }
    }
}

data class ExistingBookClaim(
    val bookId: String,
    val files: List<BookFileEntity>
)
