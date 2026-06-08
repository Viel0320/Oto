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

        // Same-Name Text Priority (Pins manifest-anchored description selection)
        // A txt sibling matching the manifest base name should win over generic metadata filenames.
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

        // Strict Text Selection (Prevents unrelated generic sidecars when callers require a manifest anchor)
        // This preserves the strict mode contract for parsers that need exact sidecar ownership.
        assertNull(selected)
    }

    @Test
    fun `common description names beat single-file fallback`() {
        val files = listOf(
            sampleRef("notes.txt"),
            sampleRef("readme.txt")
        )

        val selected = ManifestSidecarSelectionPolicy.selectTextDescription(textFiles = files)

        // Common Text Name Priority (Keeps conventional metadata files ahead of arbitrary txt siblings)
        // Directories with several text files should choose a known description name instead of the first unrelated note.
        assertEquals("readme.txt", selected?.displayName)
    }

    @Test
    fun `single text file is selected when no stronger candidate exists`() {
        val selected = ManifestSidecarSelectionPolicy.selectTextDescription(
            textFiles = listOf(sampleRef("notes.txt"))
        )

        // Single Text Fallback (Preserves heuristic import behavior for simple directories)
        // When only one txt sibling exists, it remains the best available description candidate.
        assertEquals("notes.txt", selected?.displayName)
    }

    @Test
    fun `priority cover name wins before first image fallback`() {
        val files = listOf(
            sampleRef("page1.jpg"),
            sampleRef("folder.png")
        )

        val selected = ManifestSidecarSelectionPolicy.selectDirectoryCover(files)

        // Directory Cover Priority (Keeps stable artwork names ahead of arbitrary image order)
        // Parser and recovery flows should agree that folder/cover/artwork/front files are preferred covers.
        assertEquals("folder.png", selected?.displayName)
    }

    @Test
    fun `first image is fallback cover when no priority name exists`() {
        val selected = ManifestSidecarSelectionPolicy.selectDirectoryCover(
            listOf(sampleRef("page1.jpg"), sampleRef("page2.jpg"))
        )

        // Cover Fallback Selection (Preserves existing behavior for directories without conventional cover names)
        // The first image remains the fallback so scan ordering continues to determine generic artwork.
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
