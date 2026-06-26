package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the stage-three settings-root dependency migration.
 * Prevents SettingsViewModel and SettingsScreenDependencies from drifting back to the broad library transition entry point.
 */
class SettingsSceneArchitectureTest {

    @Test
    fun settingsViewModelConsumesSettingsRootSceneDependencies() {
        val settingsViewModelSource = resolveSourceRoot().resolve("ui/settings/SettingsViewModel.kt").readText()

        assertTrue(
            "SettingsViewModel must not import the broad library facade.",
            !settingsViewModelSource.contains("import com.viel.oto.data.LibraryFacade")
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
            "SettingsViewModel must refresh root statuses through the settings-root command surface.",
            settingsViewModelSource.contains("settingsRootCommands.refreshAllRootStatuses()")
        )
        val remoteConnectionViewModelSource =
            resolveSourceRoot().resolve("ui/libraryManagement/RemoteConnectionViewModel.kt").readText()
        assertTrue(
            "RemoteConnectionViewModel must register local roots through the settings-root command surface.",
            remoteConnectionViewModelSource.contains("settingsRootCommands.addLocalRootAndScheduleSync(uri)")
        )
        assertTrue(
            "SettingsViewModel must consume the UI-owned settings root formatter.",
            settingsViewModelSource.contains("settingsRootFormatter")
        )
        assertTrue(
            "SettingsViewModel must not import the Room library root entity.",
            !settingsViewModelSource.contains("import com.viel.oto.data.entity.LibraryRootEntity")
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

        val leakingFiles = settingsSourceFiles.filter { sourceFile ->
            sourceFile.readText().contains("import com.viel.oto.data.entity.LibraryRootEntity")
        }

        assertTrue(
            "ui/settings must not import LibraryRootEntity; leaking files: ${leakingFiles.joinToString { it.name }}",
            leakingFiles.isEmpty()
        )
    }

    @Test
    fun settingsRootModuleDoesNotImportTheBroadFacade() {
        val settingsModuleSource = resolveSourceRoot()
            .resolve("application/library/settings/DefaultSettingsRootModule.kt")
            .readText()

        assertTrue(
            "DefaultSettingsRootModule must be backed by granular settings/root adapters rather than the broad facade.",
            !settingsModuleSource.contains("import com.viel.oto.data.LibraryFacade")
        )
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/oto"),
            File("app/src/main/java/com/viel/oto")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for settings scene architecture test.")
    }
}
