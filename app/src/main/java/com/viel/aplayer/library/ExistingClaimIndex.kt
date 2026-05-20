package com.viel.aplayer.library

import com.viel.aplayer.data.entity.BookFileEntity

// Derived from BookFile rows; this is the only persisted source of file ownership.
class ExistingClaimIndex private constructor(
    private val byKey: Map<String, BookFileEntity>
) {
    /**
     * 为每一次改动添加详尽的中文注释：
     * 根据物理资产身份在已入库索引中检索是否存在冲突所有权。
     * 当传入可选的 currentParentUri 时，检索将自动被限缩在同一个物理文件夹（同级目录）下，
     * 只有当已占用的物理文件的父目录与当前被认领文件的父目录完全相同时，才判定为已被抢占，
     * 跨物理文件夹的同名音频不视为抢占冲突，完美实现局域化抢占。
     * 
     * @param identity 被认领文件的物理身份
     * @param currentParentUri 当前正在被认领的文件所属的物理父目录 Uri
     * @return 产生抢占冲突的已有 BookFileEntity，无冲突则返回 null
     */
    fun find(identity: FileIdentity, currentParentUri: String? = null): BookFileEntity? {
        val found = identity.keys().firstNotNullOfOrNull { byKey[it] } ?: return null
        if (currentParentUri == null) return found
        
        // 详尽的中文注释：提取已占用物理资产与当前比对资产的父级 URI。
        // substringBeforeLast 在找不到斜杠时会安全返回 missingDelimiterValue（即空字符串）。
        val foundParent = found.uri.substringBeforeLast('/', missingDelimiterValue = "")
        val currentParent = currentParentUri.substringBeforeLast('/', missingDelimiterValue = "")
        
        return if (foundParent.isNotBlank() && foundParent.equals(currentParent, ignoreCase = true)) {
            found
        } else {
            null
        }
    }

    fun has(identity: FileIdentity, currentParentUri: String? = null): Boolean = find(identity, currentParentUri) != null

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
                val identity = FileIdentity(file.uri, file.documentId)
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