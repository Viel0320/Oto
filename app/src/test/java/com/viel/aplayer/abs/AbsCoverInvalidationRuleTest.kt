package com.viel.aplayer.abs

import com.viel.aplayer.abs.sync.resolveAbsCoverLastScannedAt
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsCoverInvalidationRuleTest {

    @Test
    fun `new abs book should use synced time when cover cache path exists`() {
        val resolved = resolveAbsCoverLastScannedAt(
            existing = null,
            nextCoverPath = "/cache/cover.jpg",
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 2000L
        )

        // Initial sync timestamp validation. When a new book obtains cover cache paths during its first sync, lastScannedAt must be set to syncedAt.
        // Otherwise, UI cache keys remain 0, providing no invalidation signal between default placeholders and newly loaded covers.
        assertEquals(2000L, resolved)
    }

    @Test
    fun `new abs book without cover cache should keep zero last scanned time`() {
        val resolved = resolveAbsCoverLastScannedAt(
            existing = null,
            nextCoverPath = null,
            nextThumbnailPath = null,
            syncedAt = 2000L
        )

        // Empty cover timestamp suppression. If a new book has no cached cover assets, do not fabricate a cover update timestamp.
        // This ensures default placeholder drawables are not falsely considered as containing loadable cover files.
        assertEquals(0L, resolved)
    }

    @Test
    fun `unchanged abs cover paths should preserve previous last scanned time`() {
        val existing = sampleAbsBook(
            coverPath = "/cache/cover.jpg",
            thumbnailPath = "/cache/thumb.jpg",
            lastScannedAt = 1000L
        )

        val resolved = resolveAbsCoverLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/cover.jpg",
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 2000L
        )

        // Timestamp preservation rule. Do not update lastScannedAt during ABS synchronization cycles if the cover paths remain unchanged.
        // Constantly refreshing the timestamp would invalidate UI cache keys pointlessly, damaging the Coil cache hit rate.
        assertEquals(1000L, resolved)
    }

    @Test
    fun `changed abs cover or thumbnail path should refresh last scanned time`() {
        val existing = sampleAbsBook(
            coverPath = "/cache/old-cover.jpg",
            thumbnailPath = "/cache/old-thumb.jpg",
            lastScannedAt = 1000L
        )

        val changedCover = resolveAbsCoverLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/new-cover.jpg",
            nextThumbnailPath = "/cache/old-thumb.jpg",
            syncedAt = 2000L
        )
        val changedThumbnail = resolveAbsCoverLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/old-cover.jpg",
            nextThumbnailPath = "/cache/new-thumb.jpg",
            syncedAt = 3000L
        )

        // Cover path variation invalidation. Any modification in the original or thumbnail cover paths indicates that the UI must generate a new key to load the updated image.
        assertEquals(2000L, changedCover)
        assertEquals(3000L, changedThumbnail)
    }

    private fun sampleAbsBook(
        coverPath: String?,
        thumbnailPath: String?,
        lastScannedAt: Long
    ): BookEntity = BookEntity(
        id = "book-1",
        rootId = "root-1",
        sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
        sourceRoot = "https://abs.example.com",
        title = "ABS Book",
        coverPath = coverPath,
        thumbnailPath = thumbnailPath,
        lastScannedAt = lastScannedAt
    )
}
