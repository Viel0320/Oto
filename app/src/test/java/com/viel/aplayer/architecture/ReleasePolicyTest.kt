package com.viel.aplayer.architecture

import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.network.UnsafeNetworkPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

        // Backup Exclusion Policy (Protects device-local preferences from cloud and transfer restore)
        // The manifest must point at both rule files, and both rule files must exclude sharedpref/device.xml.
        // Update Test for Backup Rules (Ensure credentials are excluded from backups to protect secrets)
        // Add assertions verifying that webdav_credentials.xml and abs_credentials.preferences_pb are excluded from backup_rules and data_extraction_rules.
        assertTrue(manifest.contains("""android:allowBackup="true""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
        assertTrue(backupRules.contains("""<exclude domain="sharedpref" path="device.xml"/>"""))
        assertTrue(backupRules.contains("""<exclude domain="sharedpref" path="webdav_credentials.xml"/>"""))
        assertTrue(backupRules.contains("""<exclude domain="files" path="datastore/abs_credentials.preferences_pb"/>"""))
        assertTrue(extractionRules.contains("""<cloud-backup>"""))
        assertTrue(extractionRules.contains("""<device-transfer>"""))
        assertTrue(extractionRules.contains("""<exclude domain="sharedpref" path="device.xml"/>"""))
        assertTrue(extractionRules.contains("""<exclude domain="sharedpref" path="webdav_credentials.xml"/>"""))
        assertTrue(extractionRules.contains("""<exclude domain="files" path="datastore/abs_credentials.preferences_pb"/>"""))
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
}
