package com.viel.aplayer.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player Motion Source Architecture Test (Locks direct playback away from mini-player motion)
 * Verifies that direct playback entries, mini-player expansion, and full-player cover gestures
 * keep separate UI-motion boundaries instead of sharing one broad player visibility flag.
 */
class PlayerMotionSourceArchitectureTest {

    @Test
    fun directPlaybackEntriesUseDirectFullPlayerOpenApi() {
        val sourceRoot = resolveSourceRoot()
        val homeSource = sourceRoot.resolve("ui/home/HomeScreenState.kt").readText()
        val appSource = sourceRoot.resolve("ui/navigation/APlayerApp.kt").readText()

        // Home Direct Entry Contract (Catalog play buttons must bypass mini-player motion)
        // The Home container should use the direct open API so list play commands never inherit
        // stale mini-player shared-element scopes from an already-active playback card.
        assertTrue(homeSource.contains("playerViewModel.openFullPlayerFromDirect()"))

        // App-Shell Direct Entry Contract (Widget, Detail, and Search direct opens stay source-free)
        // These app-shell entry points do not provide an on-screen mini source, so they must use
        // the direct player API instead of calling the raw visibility toggle as their playback open path.
        assertTrue(
            "APlayerApp must route non-mini playback opens through openFullPlayerFromDirect.",
            appSource.split("playerViewModel.openFullPlayerFromDirect()").size >= 4
        )
    }

    @Test
    fun miniPlayerExpansionIsTheOnlyMiniMotionSource() {
        // Architecture Test Relocation (Update assertions for unified PlayerOverlay)
        // Relocate mini-player architecture checks to run against PlayerOverlay.kt after package merger.
        val sourceRoot = resolveSourceRoot()
        val playerOverlaySource = sourceRoot.resolve("ui/player/PlayerOverlay.kt").readText()

        // Mini Source Scope Gate (Expose source scopes only for MiniPlayer-origin transitions)
        // PlayerOverlay contains both components now, but its cover and container
        // scopes must be null unless the current open source is explicitly MiniPlayer.
        assertTrue(playerOverlaySource.contains("FullPlayerOpenSource.MiniPlayer"))
        assertTrue(playerOverlaySource.contains("LocalMini2PlayerSourceScope provides if (isMiniPlayerMotionSource)"))
        assertTrue(playerOverlaySource.contains("playerViewModel.openFullPlayerFromMini()"))

        // Player Target Scope Gate (Expose target scopes only for MiniPlayer-origin transitions)
        // PlayerOverlay must apply the same source check on the target side so direct full-player
        // opens cannot accidentally match the mini-player shared bounds or cover key.
        assertTrue(playerOverlaySource.contains("settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer"))
    }

    @Test
    fun fullPlayerCoverGesturesWaitForOverlayVisibilityToSettle() {
        val playerCoverSource = resolveSourceRoot()
            .resolve("ui/common/PlayerCover.kt")
            .readText()

        // Cover Gesture Transition Gate (Prevent entry-animation drags from becoming player commands)
        // The full-player artwork should not attach drag gestures until its AnimatedVisibility host
        // has reached the stable Visible state.
        assertTrue(playerCoverSource.contains("LocalAnimatedVisibilityScope"))
        assertTrue(playerCoverSource.contains("transition.currentState == EnterExitState.Visible"))
        assertTrue(playerCoverSource.contains("val effectiveGesturesEnabled = gesturesEnabled && isHostVisibilitySettled"))
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for player motion architecture test.")
    }
}
