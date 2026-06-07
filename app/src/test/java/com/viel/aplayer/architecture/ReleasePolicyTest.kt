package com.viel.aplayer.architecture

import com.viel.aplayer.data.store.AppSettings
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
            "Cleartext Network",
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
        assertTrue(manifest.contains("""android:allowBackup="true""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
        assertTrue(backupRules.contains("""<exclude domain="sharedpref" path="device.xml"/>"""))
        assertTrue(extractionRules.contains("""<cloud-backup>"""))
        assertTrue(extractionRules.contains("""<device-transfer>"""))
        assertTrue(extractionRules.contains("""<exclude domain="sharedpref" path="device.xml"/>"""))
    }

    @Test
    fun cleartextPlatformAllowanceIsPairedWithRuntimeSettingsDefaults() {
        val networkConfig = repoFile("app/src/main/res/xml/network_security_config.xml").readText()
        val defaults = AppSettings()

        // Cleartext Priority Policy (Allows platform sockets while preserving runtime control)
        // The socket layer remains compatible with user-owned HTTP libraries, while settings own the user-facing security decision.
        assertTrue(networkConfig.contains("""<base-config cleartextTrafficPermitted="true">"""))
        assertTrue(defaults.isCleartextTrafficAllowed)
        assertTrue(!defaults.isAllowInsecureTls)
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

    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"))
        return candidates.firstOrNull { file -> file.exists() }
            ?: error("Could not locate $path from ${File(".").absolutePath}")
    }
}
