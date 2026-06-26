package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins feedback rendering to the Compose locale boundary.
 *
 * Verifies the app shell dispatches Toast-mode feedback through the same localized Context that Compose
 * publishes for Android 12L fallback language handling.
 */
class ShellFeedbackLocalizationArchitectureTest {

    @Test
    fun `app feedback dispatch should use localized context`() {
        val appSource = resolveSourceRoot()
            .resolve("ui/navigation/OtoApp.kt")
            .readText(Charsets.UTF_8)
        val eventCollector = appSource
            .substringAfter("App Event Collection")
            .substringBefore("Setup Back Navigation")

        assertTrue(
            "OtoApp feedback collection must restart when localizedContext changes.",
            eventCollector.contains("LaunchedEffect(appEventSink, appFeedbackRenderRouter, playbackViewModel, localizedContext)")
        )
        assertTrue(
            "OtoApp feedback dispatch must pass localizedContext to the renderer.",
            eventCollector.contains("context = localizedContext")
        )
    }

    private fun resolveSourceRoot() =
        ArchitectureSourceRoots.uiMain()
}
