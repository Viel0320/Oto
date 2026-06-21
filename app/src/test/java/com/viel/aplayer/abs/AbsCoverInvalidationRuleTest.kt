package com.viel.aplayer.abs

import com.viel.aplayer.data.cache.CoverCacheInvalidationPolicy
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsCoverInvalidationRuleTest {

    @Test
    fun `new abs book should use synced time when cover cache path exists`() {
        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = null,
            nextCoverPath = "/cache/cover.jpg",
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 2000L
        )

        assertEquals(2000L, resolved)
    }

    @Test
    fun `new abs book without cover cache should keep zero last scanned time`() {
        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = null,
            nextCoverPath = null,
            nextThumbnailPath = null,
            syncedAt = 2000L
        )

        assertEquals(0L, resolved)
    }

    @Test
    fun `unchanged abs cover paths should preserve previous last scanned time`() {
        val existing = sampleAbsBook(
            coverPath = "/cache/cover.jpg",
            thumbnailPath = "/cache/thumb.jpg",
            lastScannedAt = 1000L
        )

        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/cover.jpg",
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 2000L
        )

        assertEquals(1000L, resolved)
    }

    @Test
    fun `changed abs cover or thumbnail path should refresh last scanned time`() {
        val existing = sampleAbsBook(
            coverPath = "/cache/old-cover.jpg",
            thumbnailPath = "/cache/old-thumb.jpg",
            lastScannedAt = 1000L
        )

        val changedCover = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/new-cover.jpg",
            nextThumbnailPath = "/cache/old-thumb.jpg",
            syncedAt = 2000L
        )
        val changedThumbnail = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/old-cover.jpg",
            nextThumbnailPath = "/cache/new-thumb.jpg",
            syncedAt = 3000L
        )

        assertEquals(2000L, changedCover)
        assertEquals(3000L, changedThumbnail)
    }

    @Test
    fun `unchanged abs cover paths should refresh when remote version changes`() {
        val existing = sampleAbsBook(
            coverPath = "/cache/cover.jpg",
            thumbnailPath = "/cache/thumb.jpg",
            lastScannedAt = 1000L
        )

        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/cover.jpg",
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 4000L,
            remoteVersionChanged = true
        )

        assertEquals(4000L, resolved)
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
