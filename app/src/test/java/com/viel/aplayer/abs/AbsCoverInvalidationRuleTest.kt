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

        // 详尽的中文注释：新书第一次同步就拿到封面缓存路径时，必须写入 syncedAt。
        // 否则 UI 请求 key 会继续使用 0，后续新封面和旧缺省状态之间没有明确失效信号。
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

        // 详尽的中文注释：如果新书没有任何封面缓存产物，就不伪造封面更新时间。
        // 这样缺省占位图不会被误认为已有可加载封面。
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

        // 详尽的中文注释：路径没有变化时不能每轮 ABS 同步都刷新 lastScannedAt。
        // 否则所有封面请求 key 会被无意义打穿，反而破坏已建立的 Coil 缓存命中率。
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

        // 详尽的中文注释：原图或缩略图任一缓存路径变化，都说明 UI 后续应生成新 key 并加载新图。
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
