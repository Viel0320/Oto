package com.viel.aplayer.abs

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.root.shouldDeleteAbsCredential
import com.viel.aplayer.library.mergeAbsRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsLibraryRootStage1LogicTest {

    @Test
    fun `same abs server and library should reuse root and refresh credential`() {
        val existing = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "library-1",
            credentialId = "cred-1",
            displayName = "Old Name"
        )

        val merged = mergeAbsRoot(
            existingRoots = listOf(existing),
            normalizedBaseUrl = "https://example.com/audiobookshelf",
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
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "library-1",
            credentialId = "cred-1",
            displayName = "Library 1"
        )

        val merged = mergeAbsRoot(
            existingRoots = listOf(existing),
            normalizedBaseUrl = "https://example.com/audiobookshelf",
            credentialId = "cred-1",
            libraryId = "library-2",
            displayName = "Library 2",
            now = 123L,
            newRootId = "root-2"
        )

        assertEquals("root-2", merged.id)
        assertEquals("library-2", merged.basePath)
    }

    @Test
    fun `shared abs credential should not be deleted while another root still references it`() {
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "library-1",
            credentialId = "cred-1",
            displayName = "Library 1"
        )
        val other = LibraryRootEntity(
            id = "root-2",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "library-2",
            credentialId = "cred-1",
            displayName = "Library 2"
        )

        assertFalse(shouldDeleteAbsCredential(root, listOf(root, other)))
    }

    @Test
    fun `last abs credential reference should be deleted`() {
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "library-1",
            credentialId = "cred-1",
            displayName = "Library 1"
        )

        assertTrue(shouldDeleteAbsCredential(root, listOf(root)))
    }
}
