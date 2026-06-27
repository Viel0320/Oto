package com.viel.oto.library

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.root.shouldDeleteAbsCredential
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsCredentialReferencePolicyTest {

    @Test
    fun `shared abs credential should not be deleted while another root still references it`() {
        val root = absRoot(id = "root-1", libraryId = "library-1")
        val other = absRoot(id = "root-2", libraryId = "library-2")

        assertFalse(shouldDeleteAbsCredential(root, listOf(root, other)))
    }

    @Test
    fun `last abs credential reference should be deleted`() {
        val root = absRoot(id = "root-1", libraryId = "library-1")

        assertTrue(shouldDeleteAbsCredential(root, listOf(root)))
    }

    private fun absRoot(id: String, libraryId: String) = LibraryRootEntity(
        id = id,
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/AudiobookShelf",
        basePath = libraryId,
        credentialId = "cred-1",
        displayName = libraryId
    )
}
