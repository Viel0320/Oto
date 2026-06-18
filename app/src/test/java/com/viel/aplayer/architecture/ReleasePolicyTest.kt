package com.viel.aplayer.architecture

import com.viel.aplayer.network.UnsafeNetworkPolicy
import com.viel.aplayer.shared.settings.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
    fun backupRulesUseLintCompatiblePortableSettingsAllowlist() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val backupRules = repoFile("app/src/main/res/xml/backup_rules.xml").readText()
        val extractionRules = repoFile("app/src/main/res/xml/data_extraction_rules.xml").readText()
        val legacyBackupRules = parseBackupRules(backupRules)
        val android12ExtractionRules = parseBackupRules(extractionRules)

        // Backup Allowlist Policy (Protects device-local persistence from cloud and transfer restore)
        // Android lint rejects excludes outside an included path, so production rules must use a narrow include-only allowlist instead of invalid domain-level exclusions.
        // Portable Settings Boundary (Allow only app settings to participate in backup and transfer)
        // Room databases, credentials, search history, and runtime playback state stay device-local because no include rule covers their storage paths.
        assertTrue(manifest.contains("""android:allowBackup="true""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
        assertOnlyPortableSettingsAreIncluded(legacyBackupRules, "full-backup-content")
        assertNonPortableStateIsNotIncluded(legacyBackupRules, "full-backup-content")
        assertNoBackupExcludesAreRequired(legacyBackupRules, "full-backup-content")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertOnlyPortableSettingsAreIncluded(android12ExtractionRules, section)
            assertNonPortableStateIsNotIncluded(android12ExtractionRules, section)
            assertNoBackupExcludesAreRequired(android12ExtractionRules, section)
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
        // Title: Update schema version check (Verify database is at version 43 and contains the 41 baseline schema fixture)
        // Checks that the baseline 41 schema file remains, database version is 43, and only version 41 or newer exists after the download metadata migration.
        assertTrue(database.contains("version = 43"))
        assertTrue("Baseline schema 41.json must exist.", schemaFiles.contains("41.json"))
        val olderSchemas = schemaFiles.filter { name ->
            val version = name.substringBefore(".json").toIntOrNull()
            version != null && version < 41
        }
        assertTrue("No schemas older than 41 should exist: $olderSchemas", olderSchemas.isEmpty())

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
    fun readMediaAudioPermissionRequiresMediaStoreRuntimeImportFlow() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val productionSources = kotlinSourceFiles(repoFile("app/src/main/java/com/viel/aplayer"))
        val declaresReadMediaAudio = manifest.contains("android.permission.READ_MEDIA_AUDIO")
        val mediaStoreSourcePaths = productionSources
            .filter { file -> file.readText().containsMediaStoreAccess() }
            .map { file -> file.repoRelativePath() }
        val runtimePermissionSourcePaths = productionSources
            .filter { file -> file.readText().containsMediaPermissionRequest() }
            .map { file -> file.repoRelativePath() }

        // Shared Audio Permission Boundary (Keep broad media access paired with an explicit platform import path)
        // The local library currently relies on SAF tree grants, so READ_MEDIA_AUDIO may only return when production code both reads MediaStore and requests the dangerous permission at runtime.
        assertTrue(
            buildString {
                appendLine("READ_MEDIA_AUDIO must stay absent unless a MediaStore import flow requests it at runtime.")
                appendLine("MediaStore sources: ${mediaStoreSourcePaths.joinToString().ifBlank { "none" }}")
                appendLine("Runtime permission sources: ${runtimePermissionSourcePaths.joinToString().ifBlank { "none" }}")
            },
            !declaresReadMediaAudio || (mediaStoreSourcePaths.isNotEmpty() && runtimePermissionSourcePaths.isNotEmpty())
        )
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
        // Title: Update cleartext platform permission assertion (Match cleartextTrafficPermitted="true" configuration even if split across lines or adding tools:ignore)
        assertTrue(networkConfig.contains("""cleartextTrafficPermitted="true""""))
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
    fun releaseRetainedWarningAndErrorLogsUseSecureLogBoundary() {
        val sourceRoot = repoFile("app/src/main/java/com/viel/aplayer")
        val violations = sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.relativeTo(sourceRoot).invariantSeparatorsPath in releaseRetainedLogAllowlist }
            .flatMap { file -> findDirectReleaseRetainedLogCalls(sourceRoot, file).asSequence() }
            .toList()

        // Secure Release Log Boundary Policy (Forbid bypassing the sanitizer for retained Logcat levels)
        // Release builds keep warning and error logs, so every direct Log.w/e call outside SecureLog would risk leaking paths or exception text.
        assertTrue(
            "Direct release-retained Log.w/e calls must route through SecureLog:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
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

    // Release Retained Log Scanner (Find direct warning and error Logcat calls in production Kotlin sources)
    // The scanner checks both imported Log.w/e and fully qualified android.util.Log.w/e forms so call sites cannot bypass SecureLog by changing import style.
    private fun findDirectReleaseRetainedLogCalls(sourceRoot: File, file: File): List<String> =
        file.readLines().mapIndexedNotNull { index, line ->
            val codeOnly = line.substringBefore("//")
            if (directReleaseRetainedLogRegex.containsMatchIn(codeOnly)) {
                "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1} uses direct retained Logcat output"
            } else {
                null
            }
        }

    // Production Kotlin Source Scanner (Limit architecture guards to checked-in app code)
    // Manifest policy tests use this helper to avoid counting unit tests or generated build artifacts as production permission evidence.
    private fun kotlinSourceFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    // Repository Path Formatter (Report stable paths from either repository-root or module-root test execution)
    // Assertion messages should name the same source location regardless of the Gradle working directory.
    private fun File.repoRelativePath(): String =
        invariantSeparatorsPath.removePrefix("./").removePrefix("../")

    // MediaStore Access Detector (Recognize production code that reads Android's shared media index)
    // A READ_MEDIA_AUDIO declaration is only justified when code imports or directly references MediaStore as part of a real audio import path.
    private fun String.containsMediaStoreAccess(): Boolean =
        contains("android.provider.MediaStore") || contains("MediaStore.")

    // Dangerous Permission Request Detector (Recognize runtime permission request surfaces)
    // Android 13+ media permissions are dangerous permissions, so manifest declaration alone is insufficient proof of an active user-facing import flow.
    private fun String.containsMediaPermissionRequest(): Boolean =
        contains("android.permission.READ_MEDIA_AUDIO") && (
            contains("requestPermissions") ||
                contains("RequestPermission") ||
                contains("RequestMultiplePermissions") ||
                contains("rememberLauncherForActivityResult")
            )

    // Backup XML Parsing Model (Represent one include or exclude node with its containing rule section)
    // Parsing the XML avoids brittle string snapshots and lets policy tests reason about backup domains and paths directly.
    private data class BackupRule(
        val section: String,
        val elementName: String,
        val domain: String,
        val path: String
    )

    // Backup Path Model (Represent one persistence artifact that must remain outside migration)
    // The model lets tests compare Android backup domains and relative paths without hard-coding string parsing at each assertion site.
    private data class BackupPath(
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

    // Non-Portable Backup Boundary Guard (Ensure sensitive and volatile stores are never included)
    // The production XML is an include-only allowlist, so this assertion checks whether any include rule would cover credentials, Room files, search history, or device markers.
    private fun assertNonPortableStateIsNotIncluded(rules: List<BackupRule>, section: String) {
        val includes = rules.filter { rule -> rule.section == section && rule.elementName == "include" }
        val coveredPaths = NON_PORTABLE_BACKUP_PATHS.filter { path ->
            includes.any { include -> include.covers(path) }
        }
        assertTrue(
            "$section must not include non-portable backup paths: ${coveredPaths.joinToString { it.displayName() }}.",
            coveredPaths.isEmpty()
        )
    }

    // Lint-Compatible Backup Rule Guard (Keep the XML free of out-of-scope exclude nodes)
    // When the allowlist already names only app_settings.preferences_pb, extra excludes would be invalid under Android lint and would not add protection.
    private fun assertNoBackupExcludesAreRequired(rules: List<BackupRule>, section: String) {
        val excludes = rules.filter { rule -> rule.section == section && rule.elementName == "exclude" }
        assertTrue(
            "$section must use the include-only backup allowlist without invalid excludes: ${excludes.joinToString { it.domain + "/" + it.path }}.",
            excludes.isEmpty()
        )
    }

    // Backup Include Coverage Check (Detect broad includes that would accidentally migrate device-local state)
    // A root include or parent-directory include covers the target path, while the exact app_settings file include remains narrow enough to pass.
    private fun BackupRule.covers(target: BackupPath): Boolean {
        if (domain != target.domain) return false
        val normalizedIncludePath = path.trimEnd('/')
        val normalizedTargetPath = target.path.trimEnd('/')
        return normalizedIncludePath == "." ||
            normalizedIncludePath == normalizedTargetPath ||
            normalizedTargetPath.startsWith("$normalizedIncludePath/")
    }

    // Backup Path Display Name (Make assertion failures point at the Android backup domain and relative path)
    // The readable form keeps release policy failures actionable when a future include accidentally broadens migration scope.
    private fun BackupPath.displayName(): String = "$domain/$path"

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
        // WAL, shared-memory, and rollback journal files are tracked with the main database so broad include rules cannot revive partial Room runtime state.
        private val ROOM_DATABASE_PATHS = listOf(
            "aplayer_database",
            "aplayer_database-shm",
            "aplayer_database-wal",
            "aplayer_database-journal"
        )

        // Non-Portable Backup Inventory (Centralize persistence paths that must never be covered by backup includes)
        // The list mirrors production stores for device identity, WebDAV credentials, ABS credentials, search history, and Room runtime state.
        private val NON_PORTABLE_BACKUP_PATHS = listOf(
            BackupPath("sharedpref", "device.xml"),
            BackupPath("sharedpref", "webdav_credentials.xml"),
            BackupPath("file", "datastore/abs_credentials.preferences_pb"),
            BackupPath("file", "datastore/search_history.preferences_pb")
        ) + ROOM_DATABASE_PATHS.map { path -> BackupPath("database", path) }

        // Secure Log Allowlist (Permit only the central retained-log boundary to call Log.w/e directly)
        // Keeping this list narrow makes new release-visible logging paths fail tests until they choose SecureLog explicitly.
        private val releaseRetainedLogAllowlist = setOf("logger/SecureLog.kt")
        private val directReleaseRetainedLogRegex =
            Regex("(^|[^A-Za-z0-9_.])Log\\.([we])\\s*\\(|android\\.util\\.Log\\.([we])\\s*\\(")
    }
}
