package com.viel.aplayer.application.usecase

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the add-source duplicate guard before duplicate roots can reach persistence.
 *
 * The production connection use cases share these helpers, so the tests focus on the domain rule:
 * new forms block exact roots, while edit forms and distinct sub-libraries deliberately bypass that block.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DuplicateLibraryRootGuardTest {

    @Test
    fun `new webdav connection should block an existing endpoint and base path`() {
        val roots = listOf(
            root(
                id = "webdav-root",
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                sourceUri = "https://dav.example.test",
                basePath = "/audio"
            )
        )

        val failure = runCatching {
            requireUniqueWebDavRootForNewConnection(
                roots = roots,
                url = "https://dav.example.test",
                basePath = "/audio",
                editingRootId = null
            )
        }.exceptionOrNull()

        assertTrue(hasExistingWebDavRootForNewConnection(roots, "https://dav.example.test", "/audio", null))
        assertTrue(failure is DuplicateLibraryRootException)
        assertEquals(AudiobookSchema.LibrarySourceType.WEBDAV, (failure as DuplicateLibraryRootException).sourceType)
    }

    @Test
    fun `new webdav connection should allow same endpoint with different base path`() {
        val roots = listOf(
            root(
                id = "webdav-root",
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                sourceUri = "https://dav.example.test",
                basePath = "/audio"
            )
        )

        requireUniqueWebDavRootForNewConnection(
            roots = roots,
            url = "https://dav.example.test",
            basePath = "/other",
            editingRootId = null
        )

        assertFalse(hasExistingWebDavRootForNewConnection(roots, "https://dav.example.test", "/other", null))
    }

    @Test
    fun `editing webdav connection should bypass duplicate base url guard`() {
        val roots = listOf(
            root(
                id = "webdav-root",
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                sourceUri = "https://dav.example.test",
                basePath = "/audio"
            )
        )

        requireUniqueWebDavRootForNewConnection(
            roots = roots,
            url = "https://dav.example.test/other",
            basePath = "",
            editingRootId = "webdav-root"
        )

        assertFalse(hasExistingWebDavRootForNewConnection(roots, "https://dav.example.test/books", "", "webdav-root"))
    }

    @Test
    fun `new abs save should block an existing server and library`() {
        val roots = listOf(
            root(
                id = "abs-root",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = "https://abs.example.test/AudiobookShelf",
                basePath = "library-a"
            )
        )

        val failure = runCatching {
            requireUniqueAbsRootForNewConnection(
                roots = roots,
                baseUrl = "https://abs.example.test/AudiobookShelf/",
                libraryId = "library-a",
                editingRootId = null
            )
        }.exceptionOrNull()

        assertTrue(hasExistingAbsRootForNewConnection(roots, "https://abs.example.test/AudiobookShelf/", "library-a", null))
        assertTrue(failure is DuplicateLibraryRootException)
        assertEquals(AudiobookSchema.LibrarySourceType.ABS, (failure as DuplicateLibraryRootException).sourceType)
    }

    @Test
    fun `new abs save should allow same server with different library`() {
        val roots = listOf(
            root(
                id = "abs-root",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = "https://abs.example.test/AudiobookShelf",
                basePath = "library-a"
            )
        )

        requireUniqueAbsRootForNewConnection(
            roots = roots,
            baseUrl = "https://abs.example.test/AudiobookShelf/",
            libraryId = "library-b",
            editingRootId = null
        )

        assertFalse(hasExistingAbsRootForNewConnection(roots, "https://abs.example.test/AudiobookShelf/", "library-b", null))
    }

    @Test
    fun `editing abs connection should bypass duplicate base url guard`() {
        val roots = listOf(
            root(
                id = "abs-root",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = "https://abs.example.test/AudiobookShelf",
                basePath = "library-a"
            )
        )

        requireUniqueAbsRootForNewConnection(
            roots = roots,
            baseUrl = "https://abs.example.test/AudiobookShelf/",
            libraryId = "library-a",
            editingRootId = "abs-root"
        )

        assertFalse(hasExistingAbsRootForNewConnection(roots, "https://abs.example.test/AudiobookShelf/", "library-a", "abs-root"))
    }

    private fun root(
        id: String,
        sourceType: AudiobookSchema.LibrarySourceType,
        sourceUri: String,
        basePath: String
    ): LibraryRootEntity =
        LibraryRootEntity(
            id = id,
            sourceType = sourceType,
            sourceUri = sourceUri,
            basePath = basePath,
            displayName = id,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
}
