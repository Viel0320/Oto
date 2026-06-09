package com.viel.aplayer.architecture

import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.network.UnsafeNetworkPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ReleasePolicyTest {
    @Test
    fun releasePolicyDocumentCoversProductionPriorityAreas() {
        val policy = repoFile("docs/release-policy.md").readText()

        // Release Policy Document Coverage (Keeps production behavior audit language in one place)
        // These headings lock the document to build, R8, backup, network, and runtime settings concerns.
        listOf(
            "Priority Order",
            "Debug And Release Differences",
            "Logs",
            "Backup And Transfer",
            "Room Schema Baseline",
            "Unsafe Network",
            "Dependency Upgrade Rule"
        ).forEach { heading ->
            assertTrue("release-policy.md must cover $heading.", policy.contains("## $heading"))
        }
    }

    @Test
    fun releaseBuildShrinksCodeAndResources() {
        val gradle = repoFile("app/build.gradle.kts").readText()
        val releaseBlock = gradle.substringAfter("release {").substringBefore("}")

        // Release Shrinking Policy (Pins release builds to code and resource shrinking)
        // SDK or AGP upgrades must preserve this production packaging behavior unless release-policy.md changes first.
        assertTrue(releaseBlock.contains("isMinifyEnabled = true"))
        assertTrue(releaseBlock.contains("isShrinkResources = true"))
        assertTrue(releaseBlock.contains("proguard-rules.pro"))
    }

    @Test
    fun backupRulesExcludeDeviceScopedPreferences() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val backupRules = repoFile("app/src/main/res/xml/backup_rules.xml").readText()
        val extractionRules = repoFile("app/src/main/res/xml/data_extraction_rules.xml").readText()
        val legacyBackupRules = parseBackupRules(backupRules)
        val android12ExtractionRules = parseBackupRules(extractionRules)

        // Backup Exclusion Policy (Protects device-local preferences from cloud and transfer restore)
        // The manifest must point at both rule files, and both rule files must exclude sharedpref/device.xml.
        // Portable Settings Boundary (Allow only app settings to participate in backup and transfer)
        // Room databases, credentials, search history, and runtime playback state are device-local persistence domains and must be excluded.
        assertTrue(manifest.contains("""android:allowBackup="true""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
        assertOnlyPortableSettingsAreIncluded(legacyBackupRules, "full-backup-content")
        assertBackupStateIsExcluded(legacyBackupRules, "full-backup-content")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertOnlyPortableSettingsAreIncluded(android12ExtractionRules, section)
            assertBackupStateIsExcluded(android12ExtractionRules, section)
        }
    }

    @Test
    fun roomSchemaPolicyStartsAtVersion41AndRejectsDestructiveFallback() {
        val database = repoFile("app/src/main/java/com/viel/aplayer/data/db/AppDatabase.kt").readText()
        val policy = repoFile("docs/release-policy.md").readText()
        val schemaDir = repoFile("app/schemas/com.viel.aplayer.data.db.AppDatabase")
        val schemaFiles = schemaDir.listFiles()
            ?.filter { file -> file.extension == "json" }
            ?.map { file -> file.name }
            ?.sorted()
            ?: emptyList()

        // Room Baseline Release Guard (Locks version 41 as the first supported production schema)
        // Older schema fixtures were removed with the destructive rebuild policy, so future database changes must add explicit forward migrations instead of resurrecting pre-41 compatibility paths.
        assertTrue(policy.contains("Room schema version `41` is the first supported production migration baseline."))
        assertTrue(database.contains("version = 41"))
        assertTrue("Only schema 41 should remain as the migration baseline.", schemaFiles == listOf("41.json"))

        // Destructive Migration Ban (Prevent silent loss of progress, bookmarks, roots, and ABS mirror state)
        // Room must fail fast on unsupported schema gaps until a deliberate migration is added.
        listOf(
            "fallbackToDestructiveMigration",
            "fallbackToDestructiveMigrationFrom",
            "fallbackToDestructiveMigrationOnDowngrade"
        ).forEach { forbiddenApi ->
            assertTrue("AppDatabase must not call $forbiddenApi.", !database.contains(forbiddenApi))
        }
    }

    @Test
    fun manifestDoesNotShipPlaceholderPermissions() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()

        // Manifest Placeholder Permission Guard (Prevents fake service permissions from reaching release builds)
        // MediaSessionService access is enforced by PlaybackService controller verification, so the manifest must not advertise a TODO permission.
        assertTrue(!manifest.contains("""android:permission="TODO""""))
    }

    @Test
    fun cleartextPlatformAllowanceIsPairedWithUnsafeNetworkRuntimePolicy() {
        val networkConfig = repoFile("app/src/main/res/xml/network_security_config.xml").readText()
        val defaults = AppSettings()
        val policy = repoFile("app/src/main/java/com/viel/aplayer/network/UnsafeNetworkPolicy.kt").readText()
        val runtimeGates = listOf(
            repoFile("app/src/main/java/com/viel/aplayer/library/LibraryRootStore.kt"),
            repoFile("app/src/main/java/com/viel/aplayer/library/vfs/sourceProvider/webdav/WebDavConnectionTester.kt"),
            repoFile("app/src/main/java/com/viel/aplayer/library/vfs/sourceProvider/webdav/WebDavSourceProvider.kt"),
            repoFile("app/src/main/java/com/viel/aplayer/abs/net/AbsApiClient.kt"),
            repoFile("app/src/main/java/com/viel/aplayer/abs/sync/AbsCoverCache.kt"),
            repoFile("app/src/main/java/com/viel/aplayer/abs/vfs/AbsSourceProvider.kt"),
            repoFile("app/src/main/java/com/viel/aplayer/media/PlaybackSourcePreflight.kt")
        )

        // Unsafe Network Runtime Gate (Allows platform sockets only with centralized runtime enforcement)
        // The socket layer remains compatible with user-owned HTTP libraries, but cleartext HTTP and insecure TLS default to blocked until the global settings switches allow them.
        // ABS Cover Runtime Coverage (Includes standalone cover downloads in the transport gate inventory)
        // Cover downloads attach bearer credentials outside RealAbsApiClient, so the release policy must fail if AbsCoverCache drops UnsafeNetworkPolicy enforcement.
        assertTrue(networkConfig.contains("""<base-config cleartextTrafficPermitted="true">"""))
        assertTrue(!defaults.isCleartextTrafficAllowed)
        assertTrue(!defaults.isAllowInsecureTls)
        assertTrue(!UnsafeNetworkPolicy.isCleartextHttpAllowed("http://example.test/books", defaults))
        assertTrue(!UnsafeNetworkPolicy.isInsecureTlsAllowed(defaults))
        assertTrue(policy.contains("fun isCleartextHttpAllowed"))
        assertTrue(policy.contains("fun isInsecureTlsAllowed"))
        runtimeGates.forEach { file ->
            assertTrue("${file.path} must enforce UnsafeNetworkPolicy.", file.readText().contains("UnsafeNetworkPolicy"))
        }
    }

    @Test
    fun r8ReleaseLogPolicyStripsVerboseDebugAndInfoOnly() {
        val rules = repoFile("app/proguard-rules.pro").readText()
        val logBlock = rules.substringAfter("-assumenosideeffects class android.util.Log").substringBefore("}")

        // Release Log Stripping Policy (Removes noisy logs without hiding warning and error diagnostics)
        // The R8 rule intentionally strips v/d/i and leaves w/e outside the no-side-effects block.
        assertTrue(logBlock.contains("public static int v(...);"))
        assertTrue(logBlock.contains("public static int d(...);"))
        assertTrue(logBlock.contains("public static int i(...);"))
        assertTrue(!logBlock.contains("public static int w(...);"))
        assertTrue(!logBlock.contains("public static int e(...);"))
    }

    @Test
    fun ciWorkflowRunsDebugLintReleaseLintAndReleaseR8AsSeparateGates() {
        val workflow = repoFile(".github/workflows/ci.yml").readText()

        // CI Gate Coverage Policy (Pins release-only validation to explicit workflow cases)
        // Each command is asserted independently so release lint, R8 packaging, debug compile, and unit tests cannot silently collapse into one weak job.
        listOf(
            "name: Debug Kotlin Compile" to ".\\gradlew.bat compileDebugKotlin",
            "name: Debug Unit Tests" to ".\\gradlew.bat testDebugUnitTest",
            "name: Debug Android Lint" to ".\\gradlew.bat lintDebug",
            "name: Release Android Lint" to ".\\gradlew.bat lintRelease",
            "name: Release Assemble And R8" to ".\\gradlew.bat assembleRelease"
        ).forEach { (jobName, command) ->
            assertTrue("ci.yml must contain $jobName.", workflow.contains(jobName))
            assertTrue("ci.yml must run $command.", workflow.contains(command))
        }
        assertTrue("ci.yml must allow manual release verification.", workflow.contains("workflow_dispatch:"))
    }

    @Test
    fun r8RulesDoNotSuppressWholeMedia3OrCoilPackages() {
        val rules = repoFile("app/proguard-rules.pro").readText()

        // R8 Warning Visibility Policy (Prevents whole-library warning suppression from hiding release failures)
        // Consumer rules should carry Media3 and Coil internals; app rules may not blanket-suppress those packages.
        assertTrue(!rules.contains("-dontwarn androidx.media3.**"))
        assertTrue(!rules.contains("-dontwarn coil.**"))
    }

    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"))
        return candidates.firstOrNull { file -> file.exists() }
            ?: error("Could not locate $path from ${File(".").absolutePath}")
    }

    // Backup XML Parsing Model (Represent one include or exclude node with its containing rule section)
    // Parsing the XML avoids brittle string snapshots and lets policy tests reason about backup domains and paths directly.
    private data class BackupRule(
        val section: String,
        val elementName: String,
        val domain: String,
        val path: String
    )

    // Backup XML Parser (Extract include and exclude rules from legacy and Android 12+ backup resources)
    // Legacy full-backup-content stores rules at the root, while data-extraction-rules nests them under cloud-backup and device-transfer.
    private fun parseBackupRules(xml: String): List<BackupRule> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        val root = document.documentElement
        return if (root.tagName == "full-backup-content") {
            collectBackupRules(root, root.tagName)
        } else {
            root.childElements()
                .filter { element -> element.tagName == "cloud-backup" || element.tagName == "device-transfer" }
                .flatMap { element -> collectBackupRules(element, element.tagName) }
        }
    }

    // Backup Section Collector (Read direct include and exclude children for a single backup section)
    // Restricting collection to direct children keeps nested sections from accidentally borrowing rules from another transfer mode.
    private fun collectBackupRules(parent: Element, section: String): List<BackupRule> {
        return parent.childElements()
            .filter { element -> element.tagName == "include" || element.tagName == "exclude" }
            .map { element ->
                BackupRule(
                    section = section,
                    elementName = element.tagName,
                    domain = element.getAttribute("domain"),
                    path = element.getAttribute("path")
                )
            }
    }

    // Portable Settings Include Guard (Allow only the app_settings DataStore file to be restored)
    // Any additional include would broaden backup scope beyond user settings and could reintroduce Room or runtime state migration.
    private fun assertOnlyPortableSettingsAreIncluded(rules: List<BackupRule>, section: String) {
        val includes = rules.filter { rule -> rule.section == section && rule.elementName == "include" }
        assertTrue(
            "$section must include only portable app settings.",
            includes == listOf(BackupRule(section, "include", "file", PORTABLE_SETTINGS_PATH))
        )
    }

    // Volatile Backup Exclusion Guard (Require every non-portable persistence file to be excluded per section)
    // This documents the intended restore boundary for Room runtime rows, credentials, device-local preferences, and local UI traces.
    private fun assertBackupStateIsExcluded(rules: List<BackupRule>, section: String) {
        assertHasExclude(rules, section, "sharedpref", "device.xml")
        assertHasExclude(rules, section, "sharedpref", "webdav_credentials.xml")
        assertHasExclude(rules, section, "file", "datastore/abs_credentials.preferences_pb")
        assertHasExclude(rules, section, "file", "datastore/search_history.preferences_pb")
        ROOM_DATABASE_PATHS.forEach { path ->
            assertHasExclude(rules, section, "database", path)
        }
    }

    // Backup Exclusion Assertion (Check one domain/path pair inside the expected backup mode)
    // The message points directly at the missing XML contract so future policy regressions are quick to repair.
    private fun assertHasExclude(rules: List<BackupRule>, section: String, domain: String, path: String) {
        assertTrue(
            "$section must exclude $domain/$path.",
            rules.any { rule ->
                rule.section == section &&
                    rule.elementName == "exclude" &&
                    rule.domain == domain &&
                    rule.path == path
            }
        )
    }

    // DOM Element Iterator (Expose direct XML child elements without pulling in Android resource parsers)
    // JVM tests can inspect platform XML resources quickly while avoiding Android framework dependencies.
    private fun Element.childElements(): List<Element> {
        return buildList {
            val children = childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is Element) {
                    add(child)
                }
            }
        }
    }

    private companion object {
        // Portable Settings Path (Centralize the single DataStore file allowed to cross installs)
        // This constant mirrors AppSettingsRepository's preferencesDataStore name and keeps backup policy tests focused on settings.
        private const val PORTABLE_SETTINGS_PATH = "datastore/app_settings.preferences_pb"

        // Room Database Restore Boundary (List every SQLite sidecar that must stay out of backup and transfer)
        // WAL, shared-memory, and rollback journal files are excluded with the main database so no partial Room runtime state can revive.
        private val ROOM_DATABASE_PATHS = listOf(
            "aplayer_database",
            "aplayer_database-shm",
            "aplayer_database-wal",
            "aplayer_database-journal"
        )
    }
}
