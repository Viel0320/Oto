package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

class ExistingClaimIndex private constructor(
    private val byKey: Map<String, BookFileEntity>,
    private val sourceTypeByBookId: Map<String, AudiobookSchema.SourceType>
) {
    /**
     * Localized conflict check.
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

        val foundParent = found.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")

        return if (foundParent.equals(currentParentSourcePath, ignoreCase = true)) {
            found
        } else {
            null
        }
    }

    fun has(identity: FileIdentity, currentParentSourcePath: String? = null): Boolean =
        find(identity, currentParentSourcePath) != null

    fun sourceTypeForBook(bookId: String): AudiobookSchema.SourceType? =
        sourceTypeByBookId[bookId]

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
                val identity = FileIdentity(file.rootId, file.sourcePath, file.sourceIdentity)
                identity.keys().forEach { key -> map.putIfAbsent(key, file) }
            }
            return ExistingClaimIndex(map, books.associate { it.id to it.sourceType })
        }
    }
}

data class ExistingBookClaim(
    val bookId: String,
    val files: List<BookFileEntity>,
    val sourceType: AudiobookSchema.SourceType? = null
)
