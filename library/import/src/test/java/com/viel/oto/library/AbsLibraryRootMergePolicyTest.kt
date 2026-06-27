package com.viel.oto.library

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsLibraryRootMergePolicyTest {

    @Test
    fun `same abs server and library should reuse root and refresh credential`() {
        val existing = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
            basePath = "library-1",
            credentialId = "cred-1",
            displayName = "Old Name"
        )

        val merged = mergeAbsRoot(
            existingRoots = listOf(existing),
            normalizedBaseUrl = "https://example.com/AudiobookShelf",
            credentialId = "cred-2",
            libraryId = "library-1",
            displayName = "New Name",
            now = 123L,
            newRootId = "new-root"
        )

        assertEquals("root-1", merged.id)
        assertEquals("cred-2", merged.credentialId)
        assertEquals("New Name", merged.displayName)
    }

    @Test
    fun `different abs library on same server should create new root`() {
        val existing = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
            basePath = "library-1",
            credentialId = "cred-1",
            displayName = "Library 1"
        )

        val merged = mergeAbsRoot(
            existingRoots = listOf(existing),
            normalizedBaseUrl = "https://example.com/AudiobookShelf",
            credentialId = "cred-1",
            libraryId = "library-2",
            displayName = "Library 2",
            now = 123L,
            newRootId = "root-2"
        )

        assertEquals("root-2", merged.id)
        assertEquals("library-2", merged.basePath)
    }
}
