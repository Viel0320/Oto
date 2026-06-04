package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

// Derived from BookFile rows; this is the only persisted source of file ownership.
class ExistingClaimIndex private constructor(
    private val byKey: Map<String, BookFileEntity>
) {
    /**
     * Resolves Conflicting Ownership in Existing Claims (Localized conflict check)
     * When an optional currentParentSourcePath is provided, the query is scoped within the same VFS folder.
     * Ownership conflict is only registered if the occupied file shares the exact parent directory as the target file.
     * Identical files in different physical directories do not trigger conflicts, ensuring localized claim behavior.
     * 
     * @param identity Physical identity of the target file being claimed
     * @param currentParentSourcePath The VFS parent directory path of the target file
     * @return Conflicting BookFileEntity if found, or null if no conflict exists
     */
    fun find(identity: FileIdentity, currentParentSourcePath: String? = null): BookFileEntity? {
        val found = identity.keys().firstNotNullOfOrNull { byKey[it] } ?: return null
        if (currentParentSourcePath == null) return found
        
        // Localized Parent Folder Resolution (Scoping path resolution)
        // Computes parent folder from the claimed file's path; conflicts are evaluated solely within the same VFS directory.
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
                // Identity-Based Mapping (Migration backward compatibility)
                // Populates key mappings using only rootId/sourcePath and sourceIdentity, discarding legacy URI matching.
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
