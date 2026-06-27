package com.viel.oto.abs.sync

import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.data.cache.OnlineSourceCachePolicy
import com.viel.oto.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsIncrementalCatalogSelectorTest {

    @Test
    fun `incremental selector should skip unchanged items and keep changed or new items in detail queue`() {
        val existingMirrors = mapOf(
            "item-1" to activeMirror("item-1", remoteUpdatedAt = 100L),
            "item-2" to activeMirror("item-2", remoteUpdatedAt = 200L)
        )
        val unchangedItems = listOf(
            AbsLibraryItemDto(id = "item-1", mediaType = "book", updatedAt = 100L),
            AbsLibraryItemDto(id = "item-2", mediaType = "book", updatedAt = 200L)
        )
        val changedItems = listOf(
            AbsLibraryItemDto(id = "item-1", mediaType = "book", updatedAt = 100L),
            AbsLibraryItemDto(id = "item-2", mediaType = "book", updatedAt = 201L),
            AbsLibraryItemDto(id = "item-3", mediaType = "book", updatedAt = 50L)
        )

        assertTrue(
            selectAbsDetailCandidateIds(
                minifiedItems = unchangedItems,
                existingMirrors = existingMirrors,
                previousFullListFingerprint = "item-1:100|item-2:200",
                currentFullListFingerprint = "item-1:100|item-2:200",
                nowMillis = 10_000L
            ).isEmpty()
        )
        assertEquals(
            listOf("item-2", "item-3"),
            selectAbsDetailCandidateIds(
                minifiedItems = changedItems,
                existingMirrors = existingMirrors,
                previousFullListFingerprint = "item-1:100|item-2:200",
                currentFullListFingerprint = "item-1:100|item-2:201|item-3:50",
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun `incremental selector should refetch unchanged mirror after catalog ttl`() {
        val now = OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS + 10_000L
        val item = AbsLibraryItemDto(id = "item-1", mediaType = "book", updatedAt = 100L)
        val freshMirrors = mapOf(
            "item-1" to activeMirror(
                remoteItemId = "item-1",
                remoteUpdatedAt = 100L,
                lastSeenAt = now - OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS
            )
        )
        val staleMirrors = mapOf(
            "item-1" to freshMirrors.getValue("item-1")
                .copy(lastSeenAt = now - OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS - 1L)
        )

        assertTrue(
            selectAbsDetailCandidateIds(
                minifiedItems = listOf(item),
                existingMirrors = freshMirrors,
                previousFullListFingerprint = "item-1:100",
                currentFullListFingerprint = "item-1:100",
                nowMillis = now
            ).isEmpty()
        )
        assertEquals(
            listOf("item-1"),
            selectAbsDetailCandidateIds(
                minifiedItems = listOf(item),
                existingMirrors = staleMirrors,
                previousFullListFingerprint = "item-1:100",
                currentFullListFingerprint = "item-1:100",
                nowMillis = now
            )
        )
    }

    @Test
    fun `incremental error summary should stay compact and deterministic`() {
        val summary = buildAbsIncrementalErrorSummary(
            linkedMapOf(
                "item-2" to "HTTP_500",
                "item-3" to "TIMEOUT"
            )
        )

        assertEquals("DETAIL_ITEM_FAILED:2:first=item-2:HTTP_500", summary)
        assertNull(buildAbsIncrementalErrorSummary(emptyMap()))
    }

    private fun activeMirror(
        remoteItemId: String,
        remoteUpdatedAt: Long,
        lastSeenAt: Long = 10_000L
    ) = AbsItemMirrorEntity(
        localBookId = "book-$remoteItemId",
        rootId = "root-1",
        serverKey = "server-1",
        remoteItemId = remoteItemId,
        lastSeenAt = lastSeenAt,
        remoteUpdatedAt = remoteUpdatedAt,
        state = AudiobookSchema.AbsMirrorState.ACTIVE
    )
}
