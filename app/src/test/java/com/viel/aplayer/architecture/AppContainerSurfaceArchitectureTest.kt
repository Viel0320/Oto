package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the Koin module surface to the narrow dependency-view contracts.
 * Prevents di-owned implementations from leaking past the dependency-view boundary now that the
 * manual AppContainer/ProcessContainer pair has been retired in favor of Koin modules.
 */
class AppContainerSurfaceArchitectureTest {

    @Test
    fun dependencyViewModuleBindsEveryNarrowContract() {
        val moduleSource = resolveKoinModule("DependencyViewModule.kt").readText()
        val missingBindings = dependencyViewInterfaces.filter { interfaceName ->
            !Regex("""single<$interfaceName>""").containsMatchIn(moduleSource)
        }

        assertTrue(
            buildString {
                appendLine("DependencyViewModule must bind every narrow dependency-view interface.")
                missingBindings.forEach { missing -> appendLine("- $missing") }
            },
            missingBindings.isEmpty()
        )
    }

    @Test
    fun koinModulesDoNotExposeGraphOwnedImplementationPropertiesOnDependencyViews() {
        val moduleSource = resolveKoinModule("DependencyViewModule.kt").readText()
        val violations = graphOwnedPropertyNames.filter { propertyName ->
            Regex("""\b${Regex.escape(propertyName)}\b""").containsMatchIn(moduleSource)
        }

        assertTrue(
            buildString {
                appendLine("Dependency-view bindings must not expose di-owned implementation properties.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    private fun resolveKoinModule(fileName: String): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer/di/koin", fileName),
            File("app/src/main/java/com/viel/aplayer/di/koin", fileName)
        )
        return candidates.firstOrNull { candidate -> candidate.isFile }
            ?: error("Could not locate koin module $fileName for app-container surface architecture test.")
    }

    companion object {
        private val dependencyViewInterfaces = listOf(
            "AppShellDependencies",
            "AppFeedbackDependencies",
            "PlaybackRecoveryDependencies",
            "PlaybackRuntimeDependencies",
            "VfsPlaybackDependencies",
            "DownloadRuntimeDependencies",
            "ManualDownloadNotificationActionDependencies",
            "LibrarySyncWorkerDependencies",
            "AbsSyncWorkerDependencies",
            "SearchScreenDependencies",
            "DetailScreenDependencies",
            "HomeScreenDependencies",
            "SettingsScreenDependencies",
            "PlayerScreenDependencies",
            "EditScreenDependencies",
            "RemoteConnectionDependencies"
        )

        private val graphOwnedPropertyNames = listOf(
            "libraryRootGateway",
            "searchHistoryGateway",
            "playbackManager",
            "searchHistoryStore",
            "autoRewindManager",
            "absAuthorizedProgressSynchronizer",
            "absSyncTaskCoordinator"
        )
    }
}
