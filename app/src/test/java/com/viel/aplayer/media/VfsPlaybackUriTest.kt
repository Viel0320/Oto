package com.viel.aplayer.media

import com.viel.aplayer.data.entity.BookFileEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VfsPlaybackUriTest {
    @Test
    fun `buffered playback uri should keep the custom VFS scheme`() {
        val uri = VfsPlaybackUri.fromBookFile(testFile(), PlaybackBufferPolicy.Buffered)

        assertEquals(VfsPlaybackUri.SCHEME, uri.scheme)
        assertEquals(FILE_ID, VfsPlaybackUri.bookFileId(uri))
    }

    @Test
    fun `direct playback uri should use content scheme while preserving VFS identity`() {
        val uri = VfsPlaybackUri.fromBookFile(testFile(), PlaybackBufferPolicy.Direct)

        assertEquals("content", uri.scheme)
        assertEquals(FILE_ID, VfsPlaybackUri.bookFileId(uri))
    }

    private companion object {
        private const val FILE_ID = "file-1"

        /**
         * Creates a minimal file row because playback URI identity only depends on BookFileEntity.id.
         */
        private fun testFile(): BookFileEntity =
            BookFileEntity(
                id = FILE_ID,
                bookId = "book-1",
                rootId = "root-1",
                index = 0,
                sourcePath = "chapter.mp3",
                sourceIdentity = "chapter.mp3",
                displayName = "chapter.mp3",
                durationMs = 1_000L,
                fileSize = 1_024L,
                lastModified = 0L
            )
    }
}
