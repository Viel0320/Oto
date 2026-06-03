package com.viel.aplayer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverImageCacheRuleTest {

    @Test
    fun `small and medium scenes should prefer thumbnail path`() {
        // 详尽的中文注释：搜索结果、首页列表、迷你播放器和最近播放都属于缩略图优先场景。
        // 这个测试把规则钉在 CoverImageSourceSelector 上，避免后续 UI 文件重新散落 `thumbnailPath ?: coverPath`。
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
        // 详尽的中文注释：详情页和播放器主封面需要优先使用原图，再通过 Main1200 限制解码尺寸。
        // 如果这里误改成缩略图优先，主封面会复用低清小图，直接破坏阶段 2 的展示规则边界。
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
        // 详尽的中文注释：背景模糊只作为采样源，优先缩略图可以避免为了 64dp blur 解码高清封面。
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
        // 详尽的中文注释：选择器只负责路径优先级，不负责制造占位路径。
        // 两个路径都缺失时继续返回 null，让 UI 的占位分支自然接管。
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

        // 详尽的中文注释：同一 variant、同一路径、同一更新时间必须稳定复用 key。
        assertEquals(firstSmallKey, secondSmallKey)
        // 详尽的中文注释：不同 variant 必须隔离，防止列表小图和主封面大图互相污染内存缓存。
        assertNotEquals(firstSmallKey, mainKey)
        // 详尽的中文注释：更新时间变化必须打破旧 key，封面自愈或 ABS 换图后才能刷新 UI。
        assertNotEquals(firstSmallKey, refreshedSmallKey)
    }
}
