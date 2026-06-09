package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Snackbar Glass Architecture Rule (Pins snackbar blur to the shared glass renderer)
 *
 * Prevents transient feedback surfaces from drifting back to raw Haze material presets that can
 * over-tint snackbar backgrounds in the app's forced-dark Haze mode.
 */
class SnackbarGlassArchitectureTest {

    @Test
    fun blurSnackbarUsesSharedLiquidGlassRendererInsteadOfRawHazeMaterials() {
        val source = resolveSourceRoot().resolve("ui/common/BlurSnackbar.kt").readText()

        // Snackbar Glass Renderer Guard (Keep snackbar tint and blur aligned with dialogs and sheets)
        // Raw HazeMaterials presets can make the snackbar panel read as a black block, so snackbar Haze mode should use the app-owned renderer.
        assertTrue(
            "BlurSnackbar must use liquidGlassCompatEffect for Haze rendering.",
            source.contains("liquidGlassCompatEffect(") &&
                source.contains("LiquidGlassStyle(") &&
                source.contains("tint = snackbarGlassTint") &&
                source.contains("shape = shape")
        )
        assertTrue(
            "BlurSnackbar must not depend on raw HazeMaterials presets.",
            !source.contains("HazeMaterials") &&
                !source.contains("ExperimentalHazeMaterialsApi") &&
                !source.contains("style = HazeMaterials")
        )
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Support Gradle runs from the app module or repository root)
        // JVM architecture tests execute under different working directories in local and CI runs, so both stable candidates are accepted.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for snackbar glass architecture test.")
    }
}
