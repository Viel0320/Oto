package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Shell Feedback Localization Architecture Test (Pins transient feedback to the Compose locale boundary)
 *
 * Verifies the app shell dispatches Toast feedback through the same localized Context that Compose
 * publishes for Android 12L fallback language handling.
 */
class ShellFeedbackLocalizationArchitectureTest {

    @Test
    fun `app feedback dispatch should use localized context`() {
        val appSource = resolveSourceRoot()
            .resolve("ui/navigation/APlayerApp.kt")
            .readText(Charsets.UTF_8)
        val eventCollector = appSource
            .substringAfter("App Event Collection")
            .substringBefore("Setup Back Navigation")

        // Localized Feedback Dispatch Guard (Locks Toast dispatch to the Compose localized context)
        // The production renderer resolves FeedbackMessage resources at dispatch time, so using the base Activity context would make pre-Android 13 Toast copy ignore the in-app language.
        // Update Feedback Architecture Test (Adapts feedback collection check to the new ViewModel)
        // Checks for playbackViewModel instead of the deleted playerViewModel.
        assertTrue(
            "APlayerApp feedback collection must restart when localizedContext changes.",
            eventCollector.contains("LaunchedEffect(appEventSink, appFeedbackRenderer, playbackViewModel, localizedContext)")
        )
        assertTrue(
            "APlayerApp feedback dispatch must pass localizedContext to the renderer.",
            eventCollector.contains("context = localizedContext")
        )
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle may execute JVM tests from the app module or repository root, so both stable source-root candidates are accepted.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for shell feedback localization test.")
    }
}
