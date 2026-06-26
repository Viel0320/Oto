package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the custom Home filter control boundary.
 *
 * Protects the Home filter row from reintroducing Material3 FilterChip while keeping the
 * custom implementation aligned with Material-like visual sizing and Android touch targets.
 */
class HomeFilterChipArchitectureTest {

    @Test
    fun homeFilterChipDoesNotDelegateToOfficialMaterialFilterChip() {
        val source = resolveSourceRoot().resolve("ui/common/OtoFilterChip.kt").readText()

        listOf(
            "import androidx.compose.material3.FilterChip",
            "import androidx.compose.material3.FilterChipDefaults",
            "androidx.compose.material3.FilterChip"
        ).forEach { forbiddenToken ->
            assertTrue(
                "OtoFilterChip must not use $forbiddenToken.",
                !source.contains(forbiddenToken)
            )
        }

        assertTrue(
            "OtoFilterChip must not call the official FilterChip composable.",
            !Regex("""(?<!Oto)FilterChip\s*\(""").containsMatchIn(source)
        )
    }

    @Test
    fun homeFilterChipKeepsMaterialVisualSizeAndAccessibleTouchTarget() {
        val source = resolveSourceRoot().resolve("ui/common/OtoFilterChip.kt").readText()

        listOf(
            "OtoFilterChipVisualHeight = 32.dp",
            "OtoFilterChipMinimumTouchTarget = 48.dp",
            "OtoFilterChipLeadingIconSize = 18.dp",
            "OtoFilterChipCornerRadius = 8.dp"
        ).forEach { requiredToken ->
            assertTrue(
                "OtoFilterChip must retain $requiredToken.",
                source.contains(requiredToken)
            )
        }
    }

    private fun resolveSourceRoot() =
        ArchitectureSourceRoots.uiMain()
}
