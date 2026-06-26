package com.viel.oto.data.cache

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverCacheInvalidationPolicyTest {

    @Test
    fun `new book with any cover path should use synced timestamp`() {
        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = null,
            nextCoverPath = null,
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 2000L
        )

        assertEquals(2000L, resolved)
    }

    @Test
    fun `new book without cover paths should keep zero timestamp`() {
        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = null,
            nextCoverPath = null,
            nextThumbnailPath = null,
            syncedAt = 2000L
        )

        assertEquals(0L, resolved)
    }

    @Test
    fun `existing book should refresh when original cover path changes`() {
        val existing = sampleBook(
            coverPath = "/cache/old-cover.jpg",
            thumbnailPath = "/cache/thumb.jpg",
            lastScannedAt = 1000L
        )

        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/new-cover.jpg",
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 3000L
        )

        assertEquals(3000L, resolved)
    }

    @Test
    fun `existing book should refresh when thumbnail path changes`() {
        val existing = sampleBook(
            coverPath = "/cache/cover.jpg",
            thumbnailPath = "/cache/old-thumb.jpg",
            lastScannedAt = 1000L
        )

        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/cover.jpg",
            nextThumbnailPath = "/cache/new-thumb.jpg",
            syncedAt = 3000L
        )

        assertEquals(3000L, resolved)
    }

    @Test
    fun `existing book should refresh when remote version changes`() {
        val existing = sampleBook(
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

    @Test
    fun `existing book should preserve timestamp when cover snapshot is unchanged`() {
        val existing = sampleBook(
            coverPath = "/cache/cover.jpg",
            thumbnailPath = "/cache/thumb.jpg",
            lastScannedAt = 1000L
        )

        val resolved = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existing,
            nextCoverPath = "/cache/cover.jpg",
            nextThumbnailPath = "/cache/thumb.jpg",
            syncedAt = 4000L,
            remoteVersionChanged = false
        )

        assertEquals(1000L, resolved)
    }

    private fun sampleBook(
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
