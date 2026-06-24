package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards production source against Android framework hidden API access.
 *
 * Hidden framework members can appear to work in debug builds but trigger hiddenapi warnings,
 * runtime blocking, or release-only behavior changes on real devices. Production code should use
 * public Android APIs or an explicit project-owned compatibility boundary instead of reflection
 * into android.* implementation details.
 */
class AndroidHiddenApiArchitectureTest {

    @Test
    fun productionSourceDoesNotUseAndroidHiddenApiReflection() {
        val sourceRoot = resolveSourceRoot()
        val violations = sourceRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .flatMap { file -> file.findHiddenApiReferences(sourceRoot) }
            .toList()

        assertTrue(
            buildString {
                appendLine("Production source must not use Android hidden API reflection.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    /**
     * Finds source references that are known to route around public Android framework APIs.
     */
    private fun File.findHiddenApiReferences(sourceRoot: File): List<String> {
        val text = readText()
        val relativePath = relativeTo(sourceRoot).invariantSeparatorsPath
        return bannedPatterns.flatMap { pattern ->
            pattern.regex.findAll(text).map { match ->
                val line = text.take(match.range.first).count { char -> char == '\n' } + 1
                "$relativePath:$line ${pattern.reason}"
            }
        }
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for Android hidden API architecture test.")
    }

    private data class BannedPattern(
        val reason: String,
        val regex: Regex
    )

    private companion object {
        private val bannedPatterns = listOf(
            BannedPattern(
                reason = "uses Drawable.getWrappedDrawable hidden API; use DrawableWrapper.drawable instead.",
                regex = Regex("""getWrappedDrawable""")
            ),
            BannedPattern(
                reason = "reflects into android.* framework internals; add a public-API adapter instead.",
                regex = Regex("""Class\.forName\s*\(\s*"android\.""")
            )
        )
    }
}
