package com.viel.aplayer.media

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoRewindPositionPolicyTest {
    @Test
    fun `multi-file playback rewind crosses into previous file`() {
        val files = listOf(
            sampleFile(id = "file-1", index = 0, durationMs = 10_000L),
            sampleFile(id = "file-2", index = 1, durationMs = 10_000L)
        )

        val target = AutoRewindPositionPolicy.playbackSeekTarget(
            currentMediaItemIndex = 1,
            currentPositionMs = 2_000L,
            rewindMs = 5_000L,
            files = files
        )

        // Cross-File Rewind Mapping (Documents that auto rewind operates on the global audiobook timeline)
        // A pause near the start of a later file should seek into the previous file instead of clamping inside the current track.
        assertEquals(0, target.mediaItemIndex)
        assertEquals(7_000L, target.positionMs)
        assertEquals(7_000L, target.globalPositionMs)
    }

    @Test
    fun `empty playback plan rewinds current item position only`() {
        val target = AutoRewindPositionPolicy.playbackSeekTarget(
            currentMediaItemIndex = 3,
            currentPositionMs = 9_000L,
            rewindMs = 4_000L,
            files = emptyList()
        )

        // Single-Item Fallback Mapping (Preserves controller-only rewind behavior when no BookPlaybackPlan exists)
        // Without file durations, the policy cannot change media items and returns a current-item seek target.
        assertNull(target.mediaItemIndex)
        assertEquals(5_000L, target.positionMs)
        assertEquals(5_000L, target.globalPositionMs)
    }

    @Test
    fun `cold-start progress rewind remaps file anchor`() {
        val progress = BookProgressEntity(
            bookId = "book-1",
            globalPositionMs = 12_000L,
            bookFileId = "file-2",
            currentFileIndex = 1,
            positionInFileMs = 2_000L,
            lastPlayedAt = 10L
        )
        val files = listOf(
            sampleFile(id = "file-1", index = 0, durationMs = 10_000L),
            sampleFile(id = "file-2", index = 1, durationMs = 10_000L)
        )

        val healed = AutoRewindPositionPolicy.rewoundProgress(
            progress = progress,
            rewindMs = 5_000L,
            files = files,
            now = 99L
        )

        // Persisted Anchor Remapping (Keeps cold-start recovery consistent with live multi-track rewind)
        // The saved progress moves to the target file and file-local position so future playback starts from the healed coordinate.
        assertEquals(7_000L, healed.globalPositionMs)
        assertEquals("file-1", healed.bookFileId)
        assertEquals(0, healed.currentFileIndex)
        assertEquals(7_000L, healed.positionInFileMs)
        assertEquals(99L, healed.lastPlayedAt)
    }

    @Test
    fun `cold-start progress without files keeps existing anchor and clamps at zero`() {
        val progress = BookProgressEntity(
            bookId = "book-1",
            globalPositionMs = 2_000L,
            bookFileId = "file-2",
            currentFileIndex = 1,
            positionInFileMs = 2_000L,
            lastPlayedAt = 10L
        )

        val healed = AutoRewindPositionPolicy.rewoundProgress(
            progress = progress,
            rewindMs = 5_000L,
            files = emptyList(),
            now = 99L
        )

        // Anchorless Recovery Fallback (Preserves legacy global-only correction when file rows are unavailable)
        // Existing file anchor fields are not rewritten because there is no file list available for a trustworthy remap.
        assertEquals(0L, healed.globalPositionMs)
        assertEquals("file-2", healed.bookFileId)
        assertEquals(1, healed.currentFileIndex)
        assertEquals(2_000L, healed.positionInFileMs)
        assertEquals(99L, healed.lastPlayedAt)
    }

    private fun sampleFile(
        id: String,
        index: Int,
        durationMs: Long
    ): BookFileEntity =
        BookFileEntity(
            id = id,
            bookId = "book-1",
            rootId = "root-1",
            index = index,
            sourcePath = "$id.m4b",
            sourceIdentity = id,
            displayName = "$id.m4b",
            durationMs = durationMs,
            fileSize = 1_000L,
            lastModified = 1L
        )
}
