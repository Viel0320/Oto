package com.viel.oto.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks direct playback away from mini-player motion.
 * Verifies that direct playback entries, mini-player expansion, and full-player cover artwork
 * keep separate UI-motion boundaries instead of sharing one broad player visibility flag.
 */
class PlayerMotionSourceArchitectureTest {

    @Test
    fun directPlaybackEntriesUseDirectFullPlayerOpenApi() {
        val sourceRoot = resolveSourceRoot()
        val homeSource = sourceRoot.resolve("ui/home/HomeUiState.kt").readText()
        val appSource = sourceRoot.resolve("ui/navigation/OtoApp.kt").readText()

        assertTrue(homeSource.contains("settingsViewModel.openFullPlayerFromDirect()"))

        assertTrue(
            "OtoApp must route non-mini playback opens through openFullPlayerFromDirect.",
            appSource.split("playerSettingsViewModel.openFullPlayerFromDirect()").size >= 4
        )
    }

    @Test
    fun miniPlayerExpansionIsTheOnlyMiniMotionSource() {
        val sourceRoot = resolveSourceRoot()
        val playerOverlaySource = sourceRoot.resolve("ui/player/PlayerOverlay.kt").readText()

        assertTrue(playerOverlaySource.contains("FullPlayerOpenSource.MiniPlayer"))
        assertTrue(playerOverlaySource.contains("LocalMini2PlayerSourceScope provides if (isMiniPlayerMotionSource)"))
        assertTrue(playerOverlaySource.contains("settingsViewModel.openFullPlayerFromMini()"))

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

        assertTrue(playerCoverSource.contains("LocalMini2PlayerTargetScope"))
        assertTrue(playerCoverSource.contains("sharedElementVisibilityScope ?: mini2PlayerTargetScope"))
        assertTrue(playerCoverSource.contains("Modifier.sharedElement("))
        assertTrue(playerCoverSource.contains("CoverImage("))
        assertTrue(playerCoverSource.contains("CoverImageVariant.Original"))

        assertTrue(playerCoverSource.contains("LocalMini2PlayerSourceCover"))
        assertTrue(playerCoverSource.contains("CoverImageVariant.ThumbnailSmall"))
        assertTrue(playerCoverSource.contains("Animatable(0f)"))

        assertTrue(playerCoverSource.contains("resolveInitialMiniOriginBridgeSource("))
        assertTrue(playerCoverSource.contains("transition.targetState == EnterExitState.Visible"))
        assertTrue(playerCoverSource.contains("transition.currentState != EnterExitState.Visible"))

        assertFalse(playerCoverSource.contains("MiniPlayerCoverBridgeIdentity"))
        assertFalse(playerCoverSource.contains("transitionCoverPath"))
        assertFalse(playerCoverSource.contains("useTransitionArtwork"))
        assertFalse(playerCoverSource.contains("CrossfadingCoverImage"))
        assertFalse(playerCoverSource.contains("gesturesEnabled"))
        assertFalse(playerCoverSource.contains("effectiveGesturesEnabled"))
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/oto"),
            File("app/src/main/java/com/viel/oto")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for player motion architecture test.")
    }
}
