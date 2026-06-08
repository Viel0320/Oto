package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings Scene Architecture Test (Pins the stage-three settings-root dependency migration)
 * Prevents SettingsViewModel and SettingsScreenDependencies from drifting back to the broad library transition entry point.
 */
class SettingsSceneArchitectureTest {

    @Test
    fun settingsViewModelConsumesSettingsRootSceneDependencies() {
        val settingsViewModelSource = resolveSourceRoot().resolve("ui/settings/SettingsViewModel.kt").readText()

        assertTrue(
            "SettingsViewModel must not import the broad library facade.",
            !settingsViewModelSource.contains("import com.viel.aplayer.data.LibraryFacade")
        )
        assertTrue(
            "SettingsViewModel must not access the old dependency property for root operations.",
            !settingsViewModelSource.contains("settingsDependencies.libraryFacade") &&
                !settingsViewModelSource.contains("private val libraryFacade") &&
                !settingsViewModelSource.contains("libraryFacade.")
        )
        assertTrue(
            "SettingsViewModel must use the settings-root read model and commands.",
            settingsViewModelSource.contains("settingsRootReadModel") &&
                settingsViewModelSource.contains("settingsRootCommands")
        )
        assertTrue(
            "SettingsViewModel must route root operations through the settings-root command surface.",
            listOf(
                "settingsRootCommands.refreshAllRootStatuses()",
                "settingsRootCommands.addLocalRootAndScheduleSync(uri)",
                "settingsRootCommands.addWebDavRootAndScheduleSync(",
                "settingsRootCommands.inspectManualAbsSync(rootId)",
                "settingsRootCommands.startManualAbsSync(inspection.rootId)",
                "settingsRootCommands.startAutoAbsSync(rootId)",
                "settingsRootCommands.scheduleUserSync()"
            ).all { expectedCall -> settingsViewModelSource.contains(expectedCall) }
        )
        assertTrue(
            "SettingsViewModel must not import the Room library root entity.",
            !settingsViewModelSource.contains("import com.viel.aplayer.data.entity.LibraryRootEntity")
        )
        assertTrue(
            "SettingsViewModel must expose SettingsRootItem instead of the old LibraryRootDisplayState wrapper.",
            settingsViewModelSource.contains("StateFlow<List<SettingsRootItem>>") &&
                !settingsViewModelSource.contains("LibraryRootDisplayState")
        )
    }

    @Test
    fun settingsSceneDoesNotImportLibraryRootEntity() {
        val settingsSourceFiles = resolveSourceRoot()
            .resolve("ui/settings")
            .walkTopDown()
            .filter { sourceFile -> sourceFile.isFile && sourceFile.extension == "kt" }
            .toList()

        // Settings Directory Entity Guard (Covers every Kotlin source owned by the settings scene)
        // Scanning the full ui/settings tree protects SettingsScreen, dialogs, sections, and state holders from re-importing the persisted root row.
        val leakingFiles = settingsSourceFiles.filter { sourceFile ->
            sourceFile.readText().contains("import com.viel.aplayer.data.entity.LibraryRootEntity")
        }

        assertTrue(
            "ui/settings must not import LibraryRootEntity; leaking files: ${leakingFiles.joinToString { it.name }}",
            leakingFiles.isEmpty()
        )
    }

    @Test
    fun settingsDependencyViewDoesNotInheritLibraryPresentationDependencies() {
        val dependenciesSource = resolveSourceRoot().resolve("dependencies/PresentationDependencies.kt").readText()
        val settingsInterface = dependenciesSource.substringAfter("interface SettingsScreenDependencies")
            .substringBefore("/**\n * Player Screen Dependencies")

        assertTrue(
            "SettingsScreenDependencies must not inherit LibraryPresentationDependencies because that would re-expose the broad root command surface.",
            !settingsInterface.substringBefore("{").contains("LibraryPresentationDependencies")
        )
        assertTrue(
            "SettingsScreenDependencies must expose the settings-root read model.",
            settingsInterface.contains("val settingsRootReadModel: SettingsRootReadModel")
        )
        assertTrue(
            "SettingsScreenDependencies must expose the settings-root command interface.",
            settingsInterface.contains("val settingsRootCommands: SettingsRootCommands")
        )
    }

    @Test
    fun settingsRootModuleDoesNotImportTheBroadFacade() {
        val settingsModuleSource = resolveSourceRoot()
            .resolve("application/library/settings/DefaultSettingsRootModule.kt")
            .readText()

        assertTrue(
            "DefaultSettingsRootModule must be backed by granular settings/root adapters rather than the broad facade.",
            !settingsModuleSource.contains("import com.viel.aplayer.data.LibraryFacade")
        )
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for settings scene architecture test.")
    }
}
