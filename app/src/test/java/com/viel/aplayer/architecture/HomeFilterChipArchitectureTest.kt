package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the custom Home filter control boundary.
 *
 * Protects the Home filter row from reintroducing Material3 FilterChip while keeping the
 * custom implementation aligned with Material-like visual sizing and Android touch targets.
 */
class HomeFilterChipArchitectureTest {

    @Test
    fun homeFilterChipDoesNotDelegateToOfficialMaterialFilterChip() {
        val source = resolveSourceRoot().resolve("ui/common/APlayerFilterChip.kt").readText()

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

        assertTrue(
            "APlayerFilterChip must not call the official FilterChip composable.",
            !Regex("""(?<!APlayer)FilterChip\s*\(""").containsMatchIn(source)
        )
    }

    @Test
    fun homeFilterChipKeepsMaterialVisualSizeAndAccessibleTouchTarget() {
        val source = resolveSourceRoot().resolve("ui/common/APlayerFilterChip.kt").readText()

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
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for Home filter chip architecture test.")
    }
}
