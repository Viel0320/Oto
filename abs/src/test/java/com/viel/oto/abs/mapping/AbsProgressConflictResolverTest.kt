package com.viel.oto.abs.mapping

import com.viel.oto.abs.net.dto.AbsUserProgressDto
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsProgressConflictResolverTest {

    private val resolver = AbsProgressConflictResolver()

    private fun localProgress(positionMs: Long, lastPlayedAt: Long = 0L) =
        BookProgressEntity(
            bookId = "book-1",
            globalPositionMs = positionMs,
            lastPlayedAt = lastPlayedAt
        )

    // region resolve()

    @Test
    fun `resolve treats missing remote progress as not started`() {
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local = localProgress(0L), remote = null)
        )
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolve(local = localProgress(30_001L), remote = null)
        )
    }

    @Test
    fun `resolve treats remote payload without a position as not started`() {
        // Missing currentTime and incomplete ratio-duration data both normalize to a 0ms remote checkpoint.
        val remoteNoTime = AbsUserProgressDto(progress = 0.5, duration = null)
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local = localProgress(1_000L), remote = remoteNoTime)
        )
        val remoteNoProgress = AbsUserProgressDto(progress = null, duration = 100.0)
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolve(local = localProgress(30_001L), remote = remoteNoProgress)
        )
    }

    @Test
    fun `resolve treats missing local progress as not started`() {
        val remote = AbsUserProgressDto(currentTime = 10.0)
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local = null, remote = remote)
        )
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolve(local = null, remote = AbsUserProgressDto(currentTime = 40.001))
        )
    }

    @Test
    fun `resolve returns Conflict on finished-vs-unfinished disagreement even when positions match`() {
        // currentTime 10s -> 10_000ms; local at the same position so position rule alone would be InSync.
        val remote = AbsUserProgressDto(currentTime = 10.0, isFinished = false)
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolve(
                local = localProgress(10_000L),
                remote = remote,
                localReadStatus = AudiobookSchema.ReadStatus.FINISHED
            )
        )
    }

    @Test
    fun `resolve ignores finished check when read status is null`() {
        val remote = AbsUserProgressDto(currentTime = 10.0, isFinished = true)
        // No localReadStatus -> hasFinishedConflict short-circuits to false -> positions decide (InSync).
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local = localProgress(10_000L), remote = remote, localReadStatus = null)
        )
    }

    @Test
    fun `resolve treats missing local progress as unfinished for finished checks`() {
        val remote = AbsUserProgressDto(currentTime = 0.0, isFinished = false)
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(
                local = null,
                remote = remote,
                localReadStatus = AudiobookSchema.ReadStatus.FINISHED
            )
        )
    }

    @Test
    fun `resolve treats positions within threshold as InSync`() {
        // remote 40s -> 40_000ms; local 10_000ms; delta 30_000 == threshold -> InSync.
        val remote = AbsUserProgressDto(currentTime = 40.0)
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local = localProgress(10_000L), remote = remote)
        )
    }

    @Test
    fun `resolve treats positions beyond threshold as Conflict`() {
        // remote 40.001s -> 40_001ms; local 10_000ms; delta 30_001 > threshold -> Conflict.
        val remote = AbsUserProgressDto(currentTime = 40.001)
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolve(local = localProgress(10_000L), remote = remote)
        )
    }

    @Test
    fun `resolve uses ratio times duration fallback when currentTime is absent`() {
        // progress 0.5 * duration 200s = 100s -> 100_000ms; local 100_000ms -> InSync.
        val remote = AbsUserProgressDto(progress = 0.5, duration = 200.0)
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local = localProgress(100_000L), remote = remote)
        )
        // Same remote but local far away -> Conflict, proving the fallback position was actually used.
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolve(local = localProgress(0L), remote = remote)
        )
    }

    @Test
    fun `resolve coerces negative remote position to zero`() {
        // currentTime -5s would be -5_000ms but is coerced to 0; local 0 -> InSync.
        val remote = AbsUserProgressDto(currentTime = -5.0)
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local = localProgress(0L), remote = remote)
        )
    }

    // endregion

    // region resolveLocalCandidates()

    @Test
    fun `resolveLocalCandidates treats remote null as not started`() {
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolveLocalCandidates(local = localProgress(0L), remote = null)
        )
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolveLocalCandidates(local = localProgress(30_001L), remote = null)
        )
    }

    @Test
    fun `resolveLocalCandidates treats local null as not started`() {
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolveLocalCandidates(local = null, remote = localProgress(5_000L))
        )
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolveLocalCandidates(local = null, remote = localProgress(30_001L))
        )
    }

    @Test
    fun `resolveLocalCandidates finished conflict wins over near-equal positions`() {
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolveLocalCandidates(
                local = localProgress(5_000L),
                remote = localProgress(5_000L),
                localReadStatus = AudiobookSchema.ReadStatus.FINISHED,
                remoteIsFinished = false
            )
        )
    }

    @Test
    fun `resolveLocalCandidates compares already-mapped positions`() {
        // delta within threshold -> InSync; beyond -> Conflict.
        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolveLocalCandidates(local = localProgress(0L), remote = localProgress(30_000L))
        )
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolveLocalCandidates(local = localProgress(0L), remote = localProgress(30_001L))
        )
    }

    // endregion

    // region shouldUploadLocalProgress()

    @Test
    fun `shouldUploadLocalProgress allows when remote missing or in sync`() {
        assertTrue(resolver.shouldUploadLocalProgress(localProgress(0L), remote = null))
        val inSyncRemote = AbsUserProgressDto(currentTime = 0.0)
        assertTrue(resolver.shouldUploadLocalProgress(localProgress(0L), remote = inSyncRemote))
    }

    @Test
    fun `shouldUploadLocalProgress on conflict uploads only when local is newer`() {
        // Position conflict: remote 100s -> 100_000ms vs local 0ms.
        val remote = AbsUserProgressDto(currentTime = 100.0, lastUpdate = 1_000L)
        // local newer than remote checkpoint -> upload.
        assertTrue(
            resolver.shouldUploadLocalProgress(localProgress(0L, lastPlayedAt = 2_000L), remote = remote)
        )
        // local older than remote checkpoint -> do not upload.
        assertFalse(
            resolver.shouldUploadLocalProgress(localProgress(0L, lastPlayedAt = 500L), remote = remote)
        )
    }

    @Test
    fun `shouldUploadLocalProgress on conflict with no remote timestamp uploads`() {
        // remote.lastUpdate null -> isLocalNewerThanRemote returns true unconditionally.
        val remote = AbsUserProgressDto(currentTime = 100.0, lastUpdate = null)
        assertTrue(
            resolver.shouldUploadLocalProgress(localProgress(0L, lastPlayedAt = 0L), remote = remote)
        )
    }

    // endregion

    // region shouldApplyRemoteProgress()

    @Test
    fun `shouldApplyRemoteProgress is false while currently playing or remote missing`() {
        val remote = AbsUserProgressDto(currentTime = 100.0)
        assertFalse(
            resolver.shouldApplyRemoteProgress(
                local = null,
                remote = remote,
                isCurrentlyPlaying = true
            )
        )
        assertFalse(
            resolver.shouldApplyRemoteProgress(
                local = null,
                remote = null,
                isCurrentlyPlaying = false
            )
        )
    }

    @Test
    fun `shouldApplyRemoteProgress applies when local missing`() {
        val remote = AbsUserProgressDto(currentTime = 100.0, lastUpdate = 2_000L)
        assertTrue(
            resolver.shouldApplyRemoteProgress(
                local = null,
                remote = remote,
                isCurrentlyPlaying = false
            )
        )
    }

    @Test
    fun `shouldApplyRemoteProgress does not apply missing-local conflicts without remote timestamp`() {
        val remote = AbsUserProgressDto(currentTime = 100.0, lastUpdate = null)
        assertFalse(
            resolver.shouldApplyRemoteProgress(
                local = null,
                remote = remote,
                isCurrentlyPlaying = false
            )
        )
    }

    @Test
    fun `shouldApplyRemoteProgress in sync prefers newer remote timestamp`() {
        val remote = AbsUserProgressDto(currentTime = 0.0, lastUpdate = 2_000L)
        // remote newer than local -> apply.
        assertTrue(
            resolver.shouldApplyRemoteProgress(
                local = localProgress(0L, lastPlayedAt = 1_000L),
                remote = remote,
                isCurrentlyPlaying = false
            )
        )
        // remote not newer -> do not apply.
        assertFalse(
            resolver.shouldApplyRemoteProgress(
                local = localProgress(0L, lastPlayedAt = 3_000L),
                remote = remote,
                isCurrentlyPlaying = false
            )
        )
    }

    @Test
    fun `shouldApplyRemoteProgress in sync without remote timestamp does not apply`() {
        val remote = AbsUserProgressDto(currentTime = 0.0, lastUpdate = null)
        assertFalse(
            resolver.shouldApplyRemoteProgress(
                local = localProgress(0L, lastPlayedAt = 1_000L),
                remote = remote,
                isCurrentlyPlaying = false
            )
        )
    }

    @Test
    fun `shouldApplyRemoteProgress conflict applies when remote newer`() {
        val remote = AbsUserProgressDto(currentTime = 100.0, lastUpdate = 5_000L)
        assertTrue(
            resolver.shouldApplyRemoteProgress(
                local = localProgress(0L, lastPlayedAt = 1_000L),
                remote = remote,
                isCurrentlyPlaying = false
            )
        )
    }
}
