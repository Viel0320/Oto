package com.viel.aplayer.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Feedback Architecture Test (Guards producer ownership of migrated feedback)
 *
 * As feedback producers move from leaf composables to command owners and domain fact factories, this
 * test pins the boundaries that must not regress: migrated leaf UI no longer constructs feedback copy or
 * holds debounce timers, and the playback control feedback now lives behind the fact factory.
 */
class FeedbackArchitectureTest {

    @Test
    fun `player bottom bar no longer produces speed or sleep timer feedback`() {
        val source = sourceRoot().resolve("ui/player/components/PlayerBottomBar.kt").readText(Charsets.UTF_8)

        assertFalse(
            "PlayerBottomBar must not construct playback speed feedback copy after migration.",
            source.contains("FeedbackMessages.playbackSpeed")
        )
        assertFalse(
            "PlayerBottomBar must not construct sleep timer feedback copy after migration.",
            source.contains("FeedbackMessages.sleepTimer")
        )
        assertFalse(
            "PlayerBottomBar must not route feedback through onShowToast after migration.",
            source.contains("onShowToast")
        )
        assertFalse(
            "PlayerBottomBar must not import FeedbackMessages after migration.",
            source.contains("import com.viel.aplayer.event.feedback.FeedbackMessages")
        )
    }

    @Test
    fun `player bottom bar no longer holds a debounce delay`() {
        val source = sourceRoot().resolve("ui/player/components/PlayerBottomBar.kt").readText(Charsets.UTF_8)

        // UI Debounce Removal Guard (Speed/timer trailing collapse now belongs to the delivery policy)
        // The owner emits provisional facts with no timing; the policy holds and collapses rapid taps.
        assertFalse(
            "PlayerBottomBar must not import kotlinx.coroutines.delay after the debounce moved to the delivery policy.",
            source.contains("import kotlinx.coroutines.delay")
        )
    }

    @Test
    fun `chapter list no longer constructs feedback copy`() {
        val source = sourceRoot().resolve("ui/player/components/ChapterList.kt").readText(Charsets.UTF_8)

        assertFalse(
            "ChapterList must not import FeedbackMessage after migration.",
            source.contains("import com.viel.aplayer.event.feedback.FeedbackMessage")
        )
        assertFalse(
            "ChapterList must raise a missing-chapter intent, not call onShowToast.",
            source.contains("onShowToast")
        )
    }

    @Test
    fun `playback control actions no longer carry a toast callback`() {
        val source = sourceRoot().resolve("ui/player/PlaybackControlActions.kt").readText(Charsets.UTF_8)

        assertFalse(
            "PlaybackControlActions must not expose onShowToast after migration.",
            source.contains("onShowToast")
        )
    }

    @Test
    fun `detail view model download path no longer constructs download cache copy`() {
        val source = sourceRoot().resolve("ui/detail/DetailViewModel.kt").readText(Charsets.UTF_8)

        assertFalse(
            "DetailViewModel must publish download cache facts, not FeedbackMessages copy.",
            source.contains("FeedbackMessages.downloadCache")
        )
        assertFalse(
            "DetailViewModel must not construct download notification permission copy after migration.",
            source.contains("FeedbackMessages.downloadNotificationPermissionDenied")
        )
        assertFalse(
            "DetailViewModel download command must take a fact, not a raw success message.",
            source.contains("successMessage")
        )
    }

    @Test
    fun `settings view model download path no longer constructs download cache copy`() {
        val source = sourceRoot().resolve("ui/settings/SettingsViewModel.kt").readText(Charsets.UTF_8)

        assertFalse(
            "SettingsViewModel must publish download cache facts, not FeedbackMessages copy.",
            source.contains("FeedbackMessages.downloadCache")
        )
        assertFalse(
            "SettingsViewModel must not construct download notification permission copy after migration.",
            source.contains("FeedbackMessages.downloadNotificationPermissionDenied")
        )
        assertFalse(
            "SettingsViewModel download command must take a fact, not a raw success message.",
            source.contains("successMessage")
        )
    }

    @Test
    fun `library view model no longer constructs book management copy`() {
        val source = sourceRoot().resolve("ui/home/LibraryViewModel.kt").readText(Charsets.UTF_8)

        assertFalse(
            "LibraryViewModel must publish book management facts, not FeedbackMessages copy.",
            source.contains("FeedbackMessages.home")
        )
        assertFalse(
            "LibraryViewModel must not route book management feedback through showToast after migration.",
            source.contains(".showToast(")
        )
    }

    @Test
    fun `deleted book recovery view model no longer constructs recovery copy`() {
        val source = sourceRoot()
            .resolve("ui/settings/recovery/DeletedBookRecoveryViewModel.kt").readText(Charsets.UTF_8)

        assertFalse(
            "DeletedBookRecoveryViewModel must publish recovery facts, not FeedbackMessages copy.",
            source.contains("FeedbackMessages.deletedBookRecovery")
        )
        assertFalse(
            "DeletedBookRecoveryViewModel must not route recovery feedback through showToast after migration.",
            source.contains(".showToast(")
        )
    }

    @Test
    fun `production code has no remaining showToast callers`() {
        val offenders = productionSources()
            .filter { file -> file.readText(Charsets.UTF_8).contains(".showToast(") }
            .map { file -> file.name }

        assertTrue(
            "showToast is removed; app feedback must go through emitFeedback(fact). Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `production code no longer references raw text feedback`() {
        val offenders = productionSources()
            .filter { file ->
                val text = file.readText(Charsets.UTF_8)
                text.contains("FeedbackMessages.rawText") || text.contains("FeedbackMessage.RawText")
            }
            .map { file -> file.name }

        assertTrue(
            "Raw text feedback is removed; only resource-backed messages remain. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `feedback message resource construction stays inside the message factory`() {
        val offenders = productionSources()
            .filter { file -> file.name != "FeedbackMessage.kt" }
            .filter { file -> file.readText(Charsets.UTF_8).contains("FeedbackMessage.Resource(") }
            .map { file -> file.name }

        // Producers describe outcomes through fact factories that delegate copy to FeedbackMessages; only
        // the message factory may construct a Resource directly.
        assertTrue(
            "FeedbackMessage.Resource(...) must only be built in FeedbackMessage.kt. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `abs layer does not import ui packages`() {
        val offenders = productionSources()
            .filter { file -> file.invariantSeparatorsPath.contains("/abs/") }
            .filter { file -> file.readText(Charsets.UTF_8).contains("import com.viel.aplayer.ui.") }
            .map { file -> file.name }

        assertTrue(
            "ABS is an anti-corruption layer and must not import UI packages. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `media layer does not import the app event sink`() {
        val offenders = productionSources()
            .filter { file -> file.invariantSeparatorsPath.contains("/media/") }
            .filter { file -> file.readText(Charsets.UTF_8).contains("import com.viel.aplayer.event.AppEventSink") }
            .map { file -> file.name }

        // Media-core emits playback-domain events; the application-layer bridge translates them to feedback.
        assertTrue(
            "media/ must not depend on AppEventSink; route through PlaybackDomainEvent instead. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    private fun productionSources(): List<File> =
        sourceRoot().walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for feedback architecture test.")
    }
}
