package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

// Derived from BookFile rows; this is the only persisted source of file ownership.
class ExistingClaimIndex private constructor(
    private val byKey: Map<String, BookFileEntity>,
    // Existing Source Type Lookup (Ownership priority input)
    // Associates file claims with their logical book source type so conflict resolution can compare persisted owners against incoming manifests.
    private val sourceTypeByBookId: Map<String, AudiobookSchema.SourceType>
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

    // Existing Owner Source Type (Priority resolution helper)
    // Returns the persisted source type for a conflicting book, defaulting at call sites when legacy rows lack book metadata.
    fun sourceTypeForBook(bookId: String): AudiobookSchema.SourceType? =
        sourceTypeByBookId[bookId]

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
            val bookId = bookIds.first()
            ExistingBookClaim(
                bookId = bookId,
                files = matchedFiles,
                // Existing Claim Source Propagation (Refresh decision metadata)
                // Carries the source type forward so same-owner refresh logic can distinguish equal-priority rescans from replacements.
                sourceType = sourceTypeByBookId[bookId]
            )
        } else {
            null
        }
    }

    companion object {
        fun from(files: List<BookFileEntity>, books: List<BookEntity> = emptyList()): ExistingClaimIndex {
            val map = mutableMapOf<String, BookFileEntity>()
            files.forEach { file ->
                // Identity-Based Mapping (Migration backward compatibility)
                // Populates key mappings using only rootId/sourcePath and sourceIdentity, discarding legacy URI matching.
                val identity = FileIdentity(file.rootId, file.sourcePath, file.sourceIdentity)
                identity.keys().forEach { key -> map.putIfAbsent(key, file) }
            }
            // Book Source Snapshot (Conflict priority metadata)
            // Builds a lightweight bookId-to-sourceType map alongside file claims without changing the ownership key model.
            return ExistingClaimIndex(map, books.associate { it.id to it.sourceType })
        }
    }
}

data class ExistingBookClaim(
    val bookId: String,
    val files: List<BookFileEntity>,
    // Claim Source Type Safe: Use AudiobookSchema.SourceType? enum.
    val sourceType: AudiobookSchema.SourceType? = null
)
