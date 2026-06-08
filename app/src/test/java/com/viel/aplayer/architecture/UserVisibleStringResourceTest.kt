package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserVisibleStringResourceTest {
    @Test
    fun chineseStringResourcesDoNotContainMojibakeMarkers() {
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()

        // Chinese Resource Encoding Snapshot (Detects broken UTF-8 resources before UI rendering)
        // Common mojibake markers are checked at the resource module where localized user copy is centralized.
        val forbiddenMarkers = commonMojibakeMarkers
        forbiddenMarkers.forEach { marker ->
            assertTrue("strings.xml must not contain mojibake marker '$marker'.", !strings.contains(marker))
        }
    }

    @Test
    fun requiredFeedbackAndSettingsResourceKeysStayPresent() {
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()

        // Resource Key Snapshot (Pins migrated user-visible copy to stable resource keys)
        // These keys cover feedback, settings, widget, media-session, and chapter-list copy moved out of Kotlin.
        listOf(
            "feedback_scan_completed_with_discovered_books",
            "feedback_playback_remote_progress_save_failed",
            "feedback_playback_speed_reset",
            "feedback_playback_speed_changed",
            "feedback_sleep_timer_off",
            "feedback_sleep_timer_five_seconds",
            "feedback_sleep_timer_end_of_chapter",
            "feedback_sleep_timer_minutes",
            "feedback_sleep_motion_tracking_paused",
            "feedback_abs_background_sync_completed",
            "feedback_chapter_physical_file_missing",
            "feedback_home_book_deleted_source_kept",
            "feedback_home_read_status_finished",
            "feedback_settings_ssl_certificate_untrusted",
            "settings_library_not_synced",
            "settings_library_management_title",
            "settings_cleartext_title",
            "settings_sleep_timer_title",
            "player_widget_fallback_author",
            "media_session_add_bookmark",
            "chapter_file_unavailable_description",
            "bookmark_default_title"
        ).forEach { key ->
            assertTrue("strings.xml must define $key.", strings.contains("""name="$key""""))
        }
    }

    @Test
    fun migratedUserVisibleCallersDoNotReintroduceHardCodedFeedbackCopy() {
        val violations = guardedSourceFiles()
            .flatMap { file ->
                val text = file.readText().substringBefore("@Preview").withoutAllowedLogLines()
                forbiddenPatterns.mapNotNull { pattern ->
                    if (pattern.regex.containsMatchIn(text)) "${file.invariantSeparatorsPath} contains ${pattern.name}" else null
                }
            }

        // User Visible Copy Guard (Keeps migrated feedback and presentation callers on string resources)
        // The guard is intentionally scoped to recently migrated surfaces so logs and domain parsing rules are not misclassified as UI copy.
        assertTrue(
            buildString {
                appendLine("Migrated user-visible callers must use FeedbackMessages or stringResource/getString.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    private fun guardedSourceFiles(): List<File> =
        listOf(
            "app/src/main/java/com/viel/aplayer/ui/settings/SleepTimerManager.kt",
            "app/src/main/java/com/viel/aplayer/ui/settings/SettingsSections.kt",
            "app/src/main/java/com/viel/aplayer/ui/settings/SettingsViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/PlayerViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/components/PlaybackControls.kt",
            "app/src/main/java/com/viel/aplayer/ui/home/LibraryViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/components/ChapterList.kt",
            "app/src/main/java/com/viel/aplayer/widget/PlayerWidget.kt",
            "app/src/main/java/com/viel/aplayer/media/service/PlaybackService.kt",
            "app/src/main/java/com/viel/aplayer/abs/sync/AbsSyncTaskCoordinator.kt",
            "app/src/main/java/com/viel/aplayer/abs/net/AbsApiClient.kt",
            "app/src/main/java/com/viel/aplayer/library/scan/ScanOutcomePolicy.kt",
            "app/proguard-rules.pro"
        ).map(::repoFile)

    private data class ForbiddenPattern(
        val name: String,
        val regex: Regex
    )

    private fun String.withoutAllowedLogLines(): String =
        lineSequence()
            .filterNot { line -> line.contains("android.util.Log.") || line.contains("Logger.") }
            .joinToString(separator = "\n")

    companion object {
        private val commonMojibakeMarkers = listOf(
            "锛",
            "绛",
            "瀹",
            "濯",
            "脗",
            "脙",
            "芒",
            "鐗",
            "杩",
            "鍓",
            "閿",
            "\uFFFD"
        )

        private val forbiddenPatterns = listOf(
            ForbiddenPattern("production-removal toast TODO", Regex("""TODO:\s*Remove toast""")),
            ForbiddenPattern("hard-coded showToast string", Regex("""showToast\s*\(\s*"""")),
            ForbiddenPattern("hard-coded MediaSession display name", Regex("""setDisplayName\s*\(\s*"""")),
            ForbiddenPattern("hard-coded user-visible Chinese string", Regex(""""[^"\n]*[\u4E00-\u9FFF][^"\n]*"""")),
            ForbiddenPattern("mojibake marker", Regex(commonMojibakeMarkers.joinToString(separator = "|") { Regex.escape(it) })),
            ForbiddenPattern("hard-coded Compose Chinese title", Regex("""title\s*=\s*"[^"]*[\u4E00-\u9FFF]""")),
            ForbiddenPattern("hard-coded Compose Chinese subtitle", Regex("""subtitle\s*=\s*"[^"]*[\u4E00-\u9FFF]""")),
            ForbiddenPattern("hard-coded Chinese content description", Regex("""contentDescription\s*=\s*"[^"]*[\u4E00-\u9FFF]"""))
        )
    }

    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"))
        return candidates.firstOrNull { file -> file.exists() }
            ?: error("Could not locate $path from ${File(".").absolutePath}")
    }
}
