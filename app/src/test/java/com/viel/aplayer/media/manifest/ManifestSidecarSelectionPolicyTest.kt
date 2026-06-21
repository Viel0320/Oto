package com.viel.aplayer.media.manifest

import com.viel.aplayer.library.FileRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestSidecarSelectionPolicyTest {
    @Test
    fun `same-name text file wins over common description names`() {
        val files = listOf(
            sampleRef("description.txt"),
            sampleRef("Book One.txt")
        )

        val selected = ManifestSidecarSelectionPolicy.selectTextDescription(
            textFiles = files,
            baseName = "Book One"
        )

        assertEquals("Book One.txt", selected?.displayName)
    }

    @Test
    fun `strict same-name text selection rejects generic fallback`() {
        val files = listOf(sampleRef("description.txt"))

        val selected = ManifestSidecarSelectionPolicy.selectTextDescription(
            textFiles = files,
            baseName = "Book One",
            strictSameNameOnly = true
        )

        assertNull(selected)
    }

    @Test
    fun `common description names beat single-file fallback`() {
        val files = listOf(
            sampleRef("notes.txt"),
            sampleRef("readme.txt")
        )

        val selected = ManifestSidecarSelectionPolicy.selectTextDescription(textFiles = files)

        assertEquals("readme.txt", selected?.displayName)
    }

    @Test
    fun `single text file is selected when no stronger candidate exists`() {
        val selected = ManifestSidecarSelectionPolicy.selectTextDescription(
            textFiles = listOf(sampleRef("notes.txt"))
        )

        assertEquals("notes.txt", selected?.displayName)
    }

    @Test
    fun `priority cover name wins before first image fallback`() {
        val files = listOf(
            sampleRef("page1.jpg"),
            sampleRef("folder.png")
        )

        val selected = ManifestSidecarSelectionPolicy.selectDirectoryCover(files)

        assertEquals("folder.png", selected?.displayName)
    }

    @Test
    fun `first image is fallback cover when no priority name exists`() {
        val selected = ManifestSidecarSelectionPolicy.selectDirectoryCover(
            listOf(sampleRef("page1.jpg"), sampleRef("page2.jpg"))
        )

        assertEquals("page1.jpg", selected?.displayName)
    }

    private fun sampleRef(displayName: String): FileRef =
        FileRef(
            rootId = "root-1",
            sourcePath = displayName,
            sourceIdentity = displayName,
            parentSourcePath = "",
            parentSourceKey = "root-1:",
            parentSourceIdentity = "root-1:",
            displayName = displayName,
            fileSize = 100L,
            lastModified = 1L
        )
}
