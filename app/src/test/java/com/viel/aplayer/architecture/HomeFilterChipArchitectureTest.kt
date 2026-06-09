package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home Filter Chip Architecture Rule (Pins the custom Home filter control boundary)
 *
 * Protects the Home filter row from reintroducing Material3 FilterChip while keeping the
 * custom implementation aligned with Material-like visual sizing and Android touch targets.
 */
class HomeFilterChipArchitectureTest {

    @Test
    fun homeFilterChipDoesNotDelegateToOfficialMaterialFilterChip() {
        val source = resolveSourceRoot().resolve("ui/common/APlayerFilterChip.kt").readText()

        // Official FilterChip Import Guard (Keep Home filters on the app-owned custom renderer)
        // The component may use Material typography and colors, but it must not delegate layout, semantics, or icon sizing to Material3 FilterChip APIs.
        listOf(
            "import androidx.compose.material3.FilterChip",
            "import androidx.compose.material3.FilterChipDefaults",
            "androidx.compose.material3.FilterChip"
        ).forEach { forbiddenToken ->
            assertTrue(
                "APlayerFilterChip must not use $forbiddenToken.",
                !source.contains(forbiddenToken)
            )
        }

        // Official FilterChip Call Guard (Catch unqualified calls even if an import is hidden by aliasing later)
        // The negative look-behind excludes the app-owned APlayerFilterChip function name while catching direct FilterChip(...) calls.
        assertTrue(
            "APlayerFilterChip must not call the official FilterChip composable.",
            !Regex("""(?<!APlayer)FilterChip\s*\(""").containsMatchIn(source)
        )
    }

    @Test
    fun homeFilterChipKeepsMaterialVisualSizeAndAccessibleTouchTarget() {
        val source = resolveSourceRoot().resolve("ui/common/APlayerFilterChip.kt").readText()

        // Chip Size Contract Guard (Keep visual and accessibility dimensions intentionally separate)
        // The visible chip should stay compact like Material3 FilterChip, while the selectable node remains large enough for touch and assistive input.
        listOf(
            "APlayerFilterChipVisualHeight = 32.dp",
            "APlayerFilterChipMinimumTouchTarget = 48.dp",
            "APlayerFilterChipLeadingIconSize = 18.dp",
            "APlayerFilterChipCornerRadius = 8.dp"
        ).forEach { requiredToken ->
            assertTrue(
                "APlayerFilterChip must retain $requiredToken.",
                source.contains(requiredToken)
            )
        }
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Support Gradle runs from the app module or repository root)
        // JVM architecture tests execute under different working directories in local and CI runs, so both stable candidates are accepted.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for Home filter chip architecture test.")
    }
}
