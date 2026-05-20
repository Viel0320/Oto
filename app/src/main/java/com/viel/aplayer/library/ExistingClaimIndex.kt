package com.viel.aplayer.library

import com.viel.aplayer.data.entity.BookFileEntity

// Derived from BookFile rows; this is the only persisted source of file ownership.
class ExistingClaimIndex private constructor(
    private val byKey: Map<String, BookFileEntity>
) {
    fun find(identity: FileIdentity): BookFileEntity? =
        identity.keys().firstNotNullOfOrNull { byKey[it] }

    fun has(identity: FileIdentity): Boolean = find(identity) != null

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