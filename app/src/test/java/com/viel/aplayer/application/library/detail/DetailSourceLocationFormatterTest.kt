package com.viel.aplayer.application.library.detail

import com.viel.aplayer.application.library.LibraryBookSourceType
import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Detail Source Location Formatter Test (Locks safe source breadcrumb rendering)
 * Verifies detail source labels use registered root names and relative paths without leaking provider internals.
 */
class DetailSourceLocationFormatterTest {
    private val formatter = DetailSourceLocationFormatter()

    @Test
    fun safRootDisplaysRegisteredNameAndRelativePath() {
        val result = formatter.format(
            snapshot = snapshot(sourceType = LibraryBookSourceType.SINGLE_AUDIO),
            files = listOf(
                DetailSourceFile(
                    fileRole = AudiobookSchema.FileRole.AUDIO,
                    sourcePath = "Audiobooks/Book%20One/chapter.mp3",
                    displayName = "chapter.mp3",
                    index = 0
                )
            ),
            root = DetailSourceRoot(
                id = ROOT_ID,
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                displayName = "Audiobooks"
            )
        )

        assertEquals("SAF://Audiobooks/Book One/chapter.mp3", result)
    }

    @Test
    fun webDavRootDoesNotExposeRawUrl() {
        val rawUrl = "https://dav.example.com/audiobooks/Book%20Two/track.mp3"
        val result = formatter.format(
            snapshot = snapshot(sourceType = LibraryBookSourceType.SINGLE_AUDIO),
            files = listOf(
                DetailSourceFile(
                    fileRole = AudiobookSchema.FileRole.AUDIO,
                    sourcePath = rawUrl,
                    displayName = "track.mp3",
                    index = 0
                )
            ),
            root = DetailSourceRoot(
                id = ROOT_ID,
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                displayName = "Remote Library"
            )
        )

        assertFalse(result.contains("https://dav.example.com"))
        assertEquals("WEBDAV://Remote Library/audiobooks/Book Two/track.mp3", result)
    }

    @Test
    fun absRootDoesNotExposePlaybackApiPath() {
        val result = formatter.format(
            snapshot = snapshot(
                sourceType = LibraryBookSourceType.ABS_REMOTE,
                title = "Server Book"
            ),
            files = listOf(
                DetailSourceFile(
                    fileRole = AudiobookSchema.FileRole.AUDIO,
                    sourcePath = "/api/items/book-id/file/track.mp3",
                    displayName = "track.mp3",
                    index = 0
                )
            ),
            root = DetailSourceRoot(
                id = ROOT_ID,
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                displayName = "ABS Library"
            )
        )

        assertFalse(result.contains("/api/"))
        assertEquals("ABS://ABS Library/Server Book", result)
    }

    @Test
    fun missingRootReturnsStableFallbackText() {
        val result = formatter.format(
            snapshot = snapshot(sourceType = LibraryBookSourceType.SINGLE_AUDIO),
            files = emptyList(),
            root = null
        )

        assertEquals("SAF://Library", result)
    }

    // DetailSourceLocationFormatterTest uses the application-level source enum at the snapshot boundary.
    private fun snapshot(
        sourceType: LibraryBookSourceType,
        title: String = "Local Book"
    ): DetailSnapshot {
        // Detail Snapshot Fixture (Creates the transition snapshot without database entity dependencies)
        // Only source type, root id, and title are relevant to formatter behavior in these tests.
        return DetailSnapshot(
            item = DetailBookItem(
                id = "book-id",
                rootId = ROOT_ID,
                sourceType = sourceType,
                title = title
            )
        )
    }

    private companion object {
        private const val ROOT_ID = "root-id"
    }
}
