package com.viel.oto.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverImageCacheRuleTest {

    @Test
    fun `small and medium scenes should prefer thumbnail path`() {
        assertEquals(
            "/cache/thumb.jpg",
            CoverImageSourceSelector.small(
                thumbnailPath = "/cache/thumb.jpg",
                coverPath = "/cache/original.jpg"
            )
        )
        assertEquals(
            "/cache/thumb.jpg",
            CoverImageSourceSelector.medium(
                thumbnailPath = "/cache/thumb.jpg",
                coverPath = "/cache/original.jpg"
            )
        )
    }

    @Test
    fun `main scenes should prefer original cover path`() {
        assertEquals(
            "/cache/original.jpg",
            CoverImageSourceSelector.main(
                coverPath = "/cache/original.jpg",
                thumbnailPath = "/cache/thumb.jpg"
            )
        )
    }

    @Test
    fun `backdrop scenes should prefer thumbnail path`() {
        assertEquals(
            "/cache/thumb.jpg",
            CoverImageSourceSelector.backdrop(
                thumbnailPath = "/cache/thumb.jpg",
                coverPath = "/cache/original.jpg"
            )
        )
    }

    @Test
    fun `selector should fall back to available path and keep null when both paths missing`() {
        assertEquals("/cache/original.jpg", CoverImageSourceSelector.small(null, "/cache/original.jpg"))
        assertEquals("/cache/thumb.jpg", CoverImageSourceSelector.main(null, "/cache/thumb.jpg"))
        assertNull(CoverImageSourceSelector.small(null, null))
    }

    @Test
    fun `cache key should isolate variants and refresh when last updated changes`() {
        val sourcePath = "C:\\books\\cover.jpg"
        val firstSmallKey = CoverImageRequestFactory.cacheKey(
            sourcePath = sourcePath,
            lastUpdated = 100L,
            variant = CoverImageVariant.ThumbnailSmall
        )
        val secondSmallKey = CoverImageRequestFactory.cacheKey(
            sourcePath = sourcePath,
            lastUpdated = 100L,
            variant = CoverImageVariant.ThumbnailSmall
        )
        val mainKey = CoverImageRequestFactory.cacheKey(
            sourcePath = sourcePath,
            lastUpdated = 100L,
            variant = CoverImageVariant.Main1200
        )
        val originalKey = CoverImageRequestFactory.cacheKey(
            sourcePath = sourcePath,
            lastUpdated = 100L,
            variant = CoverImageVariant.Original
        )
        val refreshedSmallKey = CoverImageRequestFactory.cacheKey(
            sourcePath = sourcePath,
            lastUpdated = 200L,
            variant = CoverImageVariant.ThumbnailSmall
        )

        assertEquals(firstSmallKey, secondSmallKey)
        assertNotEquals(firstSmallKey, mainKey)
        assertNotEquals(originalKey, mainKey)
        assertNotEquals(firstSmallKey, refreshedSmallKey)
    }

    @Test
    fun `original variant should request source dimensions`() {
        assertEquals("original", CoverImageVariant.Original.keySegment)
        assertEquals("original", CoverImageVariant.Original.requestSizeLabel)
        assertNull(CoverImageVariant.Original.targetWidth)
        assertNull(CoverImageVariant.Original.targetHeight)
    }
}
