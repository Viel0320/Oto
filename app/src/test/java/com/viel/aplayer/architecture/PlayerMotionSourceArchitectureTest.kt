package com.viel.aplayer.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Player Motion Source Architecture Test (Locks direct playback away from mini-player motion)
 * Verifies that direct playback entries, mini-player expansion, and full-player cover artwork
 * keep separate UI-motion boundaries instead of sharing one broad player visibility flag.
 */
class PlayerMotionSourceArchitectureTest {

    @Test
    fun directPlaybackEntriesUseDirectFullPlayerOpenApi() {
        val sourceRoot = resolveSourceRoot()
        val homeSource = sourceRoot.resolve("ui/home/HomeUiState.kt").readText()
        val appSource = sourceRoot.resolve("ui/navigation/APlayerApp.kt").readText()

        // Home Direct Entry Contract (Catalog play buttons must bypass mini-player motion)
        // The Home container should use the direct open API so list play commands never inherit
        // stale mini-player shared-element scopes from an already-active playback card.
        // Update Motion Architecture Tests (Adapts motion source tests to new settings and player view model separation)
        // Replaces references to the deleted PlayerViewModel with settingsViewModel or playerSettingsViewModel to fix architecture checks.
        assertTrue(homeSource.contains("settingsViewModel.openFullPlayerFromDirect()"))

        // App-Shell Direct Entry Contract (Widget, Detail, and Search direct opens stay source-free)
        // These app-shell entry points do not provide an on-screen mini source, so they must use
        // the direct player API instead of calling the raw visibility toggle as their playback open path.
        assertTrue(
            "APlayerApp must route non-mini playback opens through openFullPlayerFromDirect.",
            appSource.split("playerSettingsViewModel.openFullPlayerFromDirect()").size >= 4
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
        assertTrue(playerOverlaySource.contains("settingsViewModel.openFullPlayerFromMini()"))

        // Player Target Scope Gate (Expose target scopes only for MiniPlayer-origin transitions)
        // PlayerOverlay must apply the same source check on the target side so direct full-player
        // opens cannot accidentally match the mini-player shared bounds or cover key.
        assertTrue(playerOverlaySource.contains("settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer"))
    }

    /**
     * Locks the flicker-free mini-origin bridge contract for the full-player cover.
     *
     * The mismatched-book bridge was reintroduced after the earlier direct-rendering simplification,
     * but on a flicker-free footing: it replays the captured mini source thumbnail while the shared
     * element flies and fades the target original in under a zero-initialized alpha gate only after
     * the transition settles. The old fragile shape (a transition cover threaded through parameters
     * into the double-layer CrossfadingCoverImage whose gate started at 1f) must not come back.
     */
    @Test
    fun fullPlayerCoverUsesFlickerFreeMiniOriginBridge() {
        val playerCoverSource = resolveSourceRoot()
            .resolve("ui/common/PlayerCover.kt")
            .readText()

        // Shared Geometry Contract (Keep the mini-player target scope and the cover shared element)
        // Matched-book and route-owned covers still rely on this geometry for their seamless morph.
        assertTrue(playerCoverSource.contains("LocalMini2PlayerTargetScope"))
        assertTrue(playerCoverSource.contains("sharedElementVisibilityScope ?: mini2PlayerTargetScope"))
        assertTrue(playerCoverSource.contains("Modifier.sharedElement("))
        assertTrue(playerCoverSource.contains("CoverImage("))
        assertTrue(playerCoverSource.contains("CoverImageVariant.Original"))

        // Flicker-Free Bridge Contract (Source thumbnail handoff under a settle + ready alpha gate)
        // The bridge consumes the captured mini source cover, flies the source thumbnail, and gates
        // the original fade on a zero-initialized alpha so a not-yet-decoded target cannot flash in.
        assertTrue(playerCoverSource.contains("LocalMini2PlayerSourceCover"))
        assertTrue(playerCoverSource.contains("CoverImageVariant.ThumbnailSmall"))
        assertTrue(playerCoverSource.contains("Animatable(0f)"))

        // Entry-Gated Bridge Contract (Only the first mini->player entry may bridge)
        // A later book or cover change inside the already-visible full player must render directly
        // instead of replaying the captured mini source artwork.
        assertTrue(playerCoverSource.contains("resolveInitialMiniOriginBridgeSource("))
        assertTrue(playerCoverSource.contains("transition.targetState == EnterExitState.Visible"))
        assertTrue(playerCoverSource.contains("transition.currentState != EnterExitState.Visible"))

        // No Fragile Path Regression (The removed parameter-threaded crossfade must stay gone)
        // The original flicker came from threading a transition cover into a double-layer crossfading
        // image whose alpha gate started at 1f; that exact shape must not return.
        assertFalse(playerCoverSource.contains("MiniPlayerCoverBridgeIdentity"))
        assertFalse(playerCoverSource.contains("transitionCoverPath"))
        assertFalse(playerCoverSource.contains("useTransitionArtwork"))
        assertFalse(playerCoverSource.contains("CrossfadingCoverImage"))
        assertFalse(playerCoverSource.contains("gesturesEnabled"))
        assertFalse(playerCoverSource.contains("effectiveGesturesEnabled"))
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
