package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
            "bookmark_default_title",
            // Related Books Header Resource Snapshot (Pins player related-section titles after recommendation localization)
            // These keys keep static and creator-name headings available to Compose and locale compatibility tests.
            "player_related_recommended",
            "player_related_more_by_author",
            "player_related_more_by_narrator",
            "player_related_recently_added"
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

    @Test
    fun localeStringResourcesStayKeyAndPlaceholderCompatible() {
        val baseResources = readUserVisibleResources(repoFile(baseStringsPath))
        val translatableBaseResources = baseResources.filterValues { resource -> resource.translatable }

        val violations = localeResourceDirs.flatMap { dir ->
            val localeResources = readUserVisibleResources(repoFile("app/src/main/res/$dir/strings.xml"))
            val missingKeys = translatableBaseResources.keys - localeResources.keys
            val extraKeys = localeResources.keys - translatableBaseResources.keys
            val placeholderMismatches = translatableBaseResources.keys.intersect(localeResources.keys)
                .mapNotNull { key ->
                    val baseResource = translatableBaseResources.getValue(key)
                    val localeResource = localeResources.getValue(key)
                    val basePlaceholders = baseResource.placeholderContract()
                    val localePlaceholders = localeResource.placeholderContract()
                    if (baseResource.kind == localeResource.kind && basePlaceholders == localePlaceholders) {
                        null
                    } else {
                        "$dir/$key base=${baseResource.kind}/$basePlaceholders locale=${localeResource.kind}/$localePlaceholders"
                    }
                }

            buildList {
                missingKeys.sorted().forEach { key -> add("$dir is missing $key") }
                extraKeys.sorted().forEach { key -> add("$dir must not override non-translatable or unknown key $key") }
                addAll(placeholderMismatches)
            }
        }

        // Locale Resource Shape Guard (Keeps every translated strings.xml aligned with the English base contract)
        // Non-translatable base resources such as the brand name stay in values/ only, while strings and plurals must preserve type and printf placeholders per key.
        assertTrue(
            buildString {
                appendLine("Locale resources must mirror translatable base keys and preserve placeholders.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun rawTextFeedbackEntryPointsStayExplicitAndNarrow() {
        val violations = kotlinSourceFiles(repoFile("app/src/main/java"))
            .mapNotNull { file ->
                val relativePath = file.repoRelativePath()
                val text = file.readText(Charsets.UTF_8)
                if (relativePath !in allowedRawTextFiles && rawTextCallRegex.containsMatchIn(text)) {
                    relativePath
                } else {
                    null
                }
            }

        // Raw Feedback Compatibility Guard (Prevents new UI feedback from bypassing resource-backed FeedbackMessage keys)
        // The only allowed rawText locations are the compatibility factory and the legacy String overload bridge.
        assertTrue(
            buildString {
                appendLine("FeedbackMessages.rawText must stay limited to explicit compatibility entry points.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun settingsComponentCopyUsesResourceLookups() {
        val violations = kotlinSourceFiles(repoFile("app/src/main/java/com/viel/aplayer/ui/settings/components"))
            .flatMap { file ->
                val text = file.readText(Charsets.UTF_8).substringBefore("@Preview")
                settingsComponentHardCodedCopyRegex.findAll(text).map { match ->
                    "${file.repoRelativePath()} contains ${match.value.trim()}"
                }
            }

        // Settings Component Copy Guard (Covers the extracted settings component package where user-facing labels live)
        // Parser rules, preview sample data, and logs stay outside this guard so only reusable Settings UI components are constrained.
        assertTrue(
            buildString {
                appendLine("Settings components must resolve visible copy through stringResource/getString.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun editScreenSavePathDoesNotPersistEnglishUnknownFallback() {
        val source = repoFile("app/src/main/java/com/viel/aplayer/ui/edit/EditBookScreen.kt")
            .readText(Charsets.UTF_8)
            .substringBefore("@Preview")

        val violations = editSavePathUnknownFallbackRegex.findAll(source)
            .map { match -> match.value.trim() }
            .toList()

        // Edit Save Fallback Guard (Keeps localized display placeholders out of persisted metadata)
        // The edit screen may render resource-backed placeholders, but save callbacks must forward the user's title for application policy validation.
        assertTrue(
            buildString {
                appendLine("EditBookScreen save paths must not convert blank titles to the English Unknown fallback.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun detailComponentsResolveAppOwnedVisibleCopyThroughResources() {
        val violations = detailComponentSourceFiles()
            .flatMap { file ->
                val text = file.readText(Charsets.UTF_8).substringBefore("@Preview")
                detailComponentHardCodedCopyRegex.findAll(text).map { match ->
                    "${file.repoRelativePath()} contains ${match.value.trim()}"
                }
            }

        // Detail Component Copy Guard (Locks detail-shell labels to Android resources)
        // The guard targets app-owned fallbacks and action labels while leaving imported book metadata and preview sample data outside the scan.
        assertTrue(
            buildString {
                appendLine("Detail components must resolve app-owned visible copy through stringResource/getString.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun relatedBooksComponentResolvesAppOwnedVisibleCopyThroughResources() {
        val violations = relatedBooksComponentSourceFiles()
            .flatMap { file ->
                val text = file.readText(Charsets.UTF_8).substringBefore("@Preview")
                relatedBooksHardCodedCopyRegex.findAll(text).map { match ->
                    "${file.repoRelativePath()} contains ${match.value.trim()}"
                }
            }

        // Related Books Copy Guard (Locks recommendation section headings to Android resources)
        // The related-books component owns player-provided headers, while book metadata and preview fixtures stay outside this scan.
        assertTrue(
            buildString {
                appendLine("Related books components must resolve app-owned visible copy through stringResource/getString.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    private fun guardedSourceFiles(): List<File> =
        listOf(
            "app/src/main/java/com/viel/aplayer/ui/settings/SleepTimerManager.kt",
            // Settings Component Guard Path (Follow the extracted settings component package after UI decomposition)
            // The old settings root path no longer owns the reusable section rows, so the guard tracks the current component boundary.
            "app/src/main/java/com/viel/aplayer/ui/settings/components/SettingsSections.kt",
            "app/src/main/java/com/viel/aplayer/ui/settings/SettingsViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/PlaybackViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/BookmarkViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/PlayerSettingsViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/components/PlaybackControls.kt",
            // Related Books Guard Path (Track player recommendation section titles after localization)
            // This component owns app-provided related-book headings, so it must stay under migrated copy guard coverage.
            "app/src/main/java/com/viel/aplayer/ui/player/components/RelatedBooksView.kt",
            "app/src/main/java/com/viel/aplayer/ui/home/LibraryViewModel.kt",
            "app/src/main/java/com/viel/aplayer/ui/player/components/ChapterList.kt",
            "app/src/main/java/com/viel/aplayer/widget/PlayerWidget.kt",
            "app/src/main/java/com/viel/aplayer/media/service/PlaybackService.kt",
            "app/src/main/java/com/viel/aplayer/abs/sync/AbsSyncTaskCoordinator.kt",
            "app/src/main/java/com/viel/aplayer/abs/net/AbsApiClient.kt",
            "app/src/main/java/com/viel/aplayer/library/scan/ScanOutcomePolicy.kt",
            "app/proguard-rules.pro"
        ).map(::repoFile)

    private fun detailComponentSourceFiles(): List<File> =
        listOf(
            // Detail Presentation Shell Guard Path (Track the detail header and action panel where fallback labels are rendered)
            // These files own blank metadata labels and playback actions, so hard-coded English here would bypass localization and TalkBack review.
            "app/src/main/java/com/viel/aplayer/ui/detail/components/DetailHeader.kt",
            "app/src/main/java/com/viel/aplayer/ui/detail/components/DetailControlPanel.kt"
        ).map(::repoFile)

    private fun relatedBooksComponentSourceFiles(): List<File> =
        listOf(
            // Related Books Presentation Guard Path (Limit hard-coded copy scanning to app-owned recommendation headers)
            // RelatedAudiobookItem renders imported metadata through shared list rows, so this guard focuses on the player tab shell.
            "app/src/main/java/com/viel/aplayer/ui/player/components/RelatedBooksView.kt"
        ).map(::repoFile)

    private data class ForbiddenPattern(
        val name: String,
        val regex: Regex
    )

    private fun String.withoutAllowedLogLines(): String =
        lineSequence()
            .filterNot { line -> line.contains("android.util.Log.") || line.contains("Logger.") }
            .joinToString(separator = "\n")

    private enum class ResourceKind {
        STRING,
        PLURAL
    }

    private data class UserVisibleResource(
        val kind: ResourceKind,
        val texts: List<String>,
        val translatable: Boolean
    )

    private fun UserVisibleResource.placeholderContract(): Set<List<String>> =
        // Resource Placeholder Contract Reader (Keep placeholder comparison on the test instance)
        // UserVisibleResource is a static nested data holder, so placeholder parsing stays in an outer helper that can access the shared regex.
        texts.map(::printfPlaceholders).toSet()

    private fun readUserVisibleResources(file: File): Map<String, UserVisibleResource> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)

        // User Visible Resource Reader (Parses XML instead of scanning text so comments and ordering do not affect guard results)
        // Strings and plurals both participate because quantity-sensitive user copy must keep locale resources type-compatible.
        val stringNodes = document.getElementsByTagName("string")
        val strings = (0 until stringNodes.length).map { index ->
            val element = stringNodes.item(index) as Element
            val name = element.getAttribute("name")
            val translatable = element.getAttribute("translatable") != "false"
            name to UserVisibleResource(
                kind = ResourceKind.STRING,
                texts = listOf(element.textContent),
                translatable = translatable
            )
        }
        val pluralNodes = document.getElementsByTagName("plurals")
        val plurals = (0 until pluralNodes.length).map { index ->
            val element = pluralNodes.item(index) as Element
            val name = element.getAttribute("name")
            val translatable = element.getAttribute("translatable") != "false"
            val itemNodes = element.getElementsByTagName("item")
            val texts = (0 until itemNodes.length).map { itemIndex ->
                itemNodes.item(itemIndex).textContent
            }
            name to UserVisibleResource(
                kind = ResourceKind.PLURAL,
                texts = texts,
                translatable = translatable
            )
        }
        return (strings + plurals).toMap()
    }

    private fun printfPlaceholders(value: String): List<String> =
        printfPlaceholderRegex.findAll(value).map { match -> match.value }.toList()

    private fun kotlinSourceFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    private fun File.repoRelativePath(): String =
        invariantSeparatorsPath.removePrefix("./").removePrefix("../")

    companion object {
        private const val baseStringsPath = "app/src/main/res/values/strings.xml"

        private val localeResourceDirs = listOf(
            "values-zh-rCN",
            "values-zh-rHK",
            "values-zh-rTW",
            "values-ja",
            "values-fr",
            "values-de",
            "values-ru",
            "values-es",
            "values-pt"
        )

        private val allowedRawTextFiles = setOf(
            "app/src/main/java/com/viel/aplayer/event/feedback/FeedbackMessage.kt",
            "app/src/main/java/com/viel/aplayer/event/AppEventSink.kt"
        )

        private val rawTextCallRegex = Regex("""\brawText\s*\(""")
        private val editSavePathUnknownFallbackRegex = Regex("""title\.ifBlank\s*\{\s*"Unknown"\s*}""")
        private val printfPlaceholderRegex = Regex("""%\d+\$[sd]""")
        private val detailComponentHardCodedCopyRegex = Regex(
            """Text\s*\(\s*"[^"\n]+"|text\s*=\s*"[^"\n]+"|contentDescription\s*=\s*"[^"\n]+"|onClickLabel\s*=\s*"[^"\n]+"|onLongClickLabel\s*=\s*"[^"\n]+"|\?:\s*"[^"\n]+""""
        )
        private val settingsComponentHardCodedCopyRegex = Regex(
            """Text\s*\(\s*"[^"\n]+"|text\s*=\s*"[^"\n]+"|contentDescription\s*=\s*"[^"\n]+"|title\s*=\s*"[^"\n]+"|subtitle\s*=\s*"[^"\n]+"|label\s*=\s*"[^"\n]+""""
        )
        private val relatedBooksHardCodedCopyRegex = Regex(
            """RelatedSectionHeader\s*\(\s*"[^"\n]+"|Text\s*\(\s*"[^"\n]+"|text\s*=\s*"[^"\n]+""""
        )

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
