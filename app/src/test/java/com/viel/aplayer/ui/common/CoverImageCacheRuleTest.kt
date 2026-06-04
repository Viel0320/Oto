package com.viel.aplayer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverImageCacheRuleTest {

    @Test
    fun `small and medium scenes should prefer thumbnail path`() {
        // Thumbnail preference verification. Search results, main feed list, mini player, and recently played scenes prefer thumbnails.
        // This test pins the selection rules on CoverImageSourceSelector, preventing individual UI files from scattered fallback expressions.
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
        // Main cover preference verification. Detail pages and the main player cover prioritize the original high-resolution cover before restricting decoding dimensions via Main1200.
        // Misconfiguring this to prefer thumbnails would cause the main cover to reuse low-res images, violating the phase 2 presentation rule boundaries.
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
        // Backdrop preference verification. Background blur only serves as a sampling source, so preferring thumbnails avoids decoding high-res covers for simple 64dp blur effects.
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
        // Selector responsibility bounds. The selector only determines path priority and does not generate placeholders.
        // When both paths are missing, it returns null to let UI placeholder branches naturally take over.
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
        val refreshedSmallKey = CoverImageRequestFactory.cacheKey(
            sourcePath = sourcePath,
            lastUpdated = 200L,
            variant = CoverImageVariant.ThumbnailSmall
        )

        // Cache key stability. The key must be consistently reused for the identical variant, path, and update timestamp.
        assertEquals(firstSmallKey, secondSmallKey)
        // Cache key variant isolation. Different variants must be isolated to prevent memory cache pollution between thumbnail and main cover images.
        assertNotEquals(firstSmallKey, mainKey)
        // Cache key invalidation. Changes in update timestamp must invalidate the old key, ensuring UI updates upon cover recovery or ABS replacements.
        assertNotEquals(firstSmallKey, refreshedSmallKey)
    }
}
