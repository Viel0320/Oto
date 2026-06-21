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

        assertTrue(policy.contains("Room schema version `41` is the first supported production migration baseline."))
        assertTrue(database.contains("version = 43"))
        assertTrue("Baseline schema 41.json must exist.", schemaFiles.contains("41.json"))
        val olderSchemas = schemaFiles.filter { name ->
            val version = name.substringBefore(".json").toIntOrNull()
            version != null && version < 41
        }
        assertTrue("No schemas older than 41 should exist: $olderSchemas", olderSchemas.isEmpty())

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

        assertTrue(
            "Direct release-retained Log.w/e calls must route through SecureLog:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun ciWorkflowRunsDebugLintReleaseLintAndReleaseR8AsSeparateGates() {
        val workflow = repoFile(".github/workflows/ci.yml").readText()

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

        assertTrue(!rules.contains("-dontwarn androidx.media3.**"))
        assertTrue(!rules.contains("-dontwarn coil.**"))
    }

    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"))
        return candidates.firstOrNull { file -> file.exists() }
            ?: error("Could not locate $path from ${File(".").absolutePath}")
    }

    private fun findDirectReleaseRetainedLogCalls(sourceRoot: File, file: File): List<String> =
        file.readLines().mapIndexedNotNull { index, line ->
            val codeOnly = line.substringBefore("//")
            if (directReleaseRetainedLogRegex.containsMatchIn(codeOnly)) {
                "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1} uses direct retained Logcat output"
            } else {
                null
            }
        }

    private fun kotlinSourceFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    private fun File.repoRelativePath(): String =
        invariantSeparatorsPath.removePrefix("./").removePrefix("../")

    private fun String.containsMediaStoreAccess(): Boolean =
        contains("android.provider.MediaStore") || contains("MediaStore.")

    private fun String.containsMediaPermissionRequest(): Boolean =
        contains("android.permission.READ_MEDIA_AUDIO") && (
            contains("requestPermissions") ||
                contains("RequestPermission") ||
                contains("RequestMultiplePermissions") ||
                contains("rememberLauncherForActivityResult")
            )

    private data class BackupRule(
        val section: String,
        val elementName: String,
        val domain: String,
        val path: String
    )

    private data class BackupPath(
        val domain: String,
        val path: String
    )

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

    private fun assertOnlyPortableSettingsAreIncluded(rules: List<BackupRule>, section: String) {
        val includes = rules.filter { rule -> rule.section == section && rule.elementName == "include" }
        assertTrue(
            "$section must include only portable app settings.",
            includes == listOf(BackupRule(section, "include", "file", PORTABLE_SETTINGS_PATH))
        )
    }

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

    private fun assertNoBackupExcludesAreRequired(rules: List<BackupRule>, section: String) {
        val excludes = rules.filter { rule -> rule.section == section && rule.elementName == "exclude" }
        assertTrue(
            "$section must use the include-only backup allowlist without invalid excludes: ${excludes.joinToString { it.domain + "/" + it.path }}.",
            excludes.isEmpty()
        )
    }

    private fun BackupRule.covers(target: BackupPath): Boolean {
        if (domain != target.domain) return false
        val normalizedIncludePath = path.trimEnd('/')
        val normalizedTargetPath = target.path.trimEnd('/')
        return normalizedIncludePath == "." ||
            normalizedIncludePath == normalizedTargetPath ||
            normalizedTargetPath.startsWith("$normalizedIncludePath/")
    }

    private fun BackupPath.displayName(): String = "$domain/$path"

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
        private const val PORTABLE_SETTINGS_PATH = "datastore/app_settings.preferences_pb"

        private val ROOM_DATABASE_PATHS = listOf(
            "aplayer_database",
            "aplayer_database-shm",
            "aplayer_database-wal",
            "aplayer_database-journal"
        )

        private val NON_PORTABLE_BACKUP_PATHS = listOf(
            BackupPath("sharedpref", "device.xml"),
            BackupPath("sharedpref", "webdav_credentials.xml"),
            BackupPath("file", "datastore/abs_credentials.preferences_pb"),
            BackupPath("file", "datastore/search_history.preferences_pb")
        ) + ROOM_DATABASE_PATHS.map { path -> BackupPath("database", path) }

        private val releaseRetainedLogAllowlist = setOf("logger/SecureLog.kt")
        private val directReleaseRetainedLogRegex =
            Regex("(^|[^A-Za-z0-9_.])Log\\.([we])\\s*\\(|android\\.util\\.Log\\.([we])\\s*\\(")
    }
}
