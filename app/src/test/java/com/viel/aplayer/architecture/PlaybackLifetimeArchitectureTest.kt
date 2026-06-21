package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks phase-six di ownership.
 * Verifies persistence di and deletion use cases stay decoupled from playback runtime managers.
 */
class PlaybackLifetimeArchitectureTest {

    @Test
    fun dataGraphDoesNotOwnPlaybackRuntimeManagers() {
        val dataGraph = resolveSourceRoot().resolve("di/graph/DataGraph.kt").readText()

        assertTrue(!dataGraph.contains("PlaybackManager"))
        assertTrue(!dataGraph.contains("AutoRewindManager"))
        assertTrue(!dataGraph.contains("playbackManager"))
        assertTrue(!dataGraph.contains("autoRewindManager"))
    }

    @Test
    fun deletionUseCasesDoNotImportPlaybackManager() {
        val sourceRoot = resolveSourceRoot()
        val useCasePaths = listOf(
            "application/usecase/BookManagementUseCase.kt",
            "application/usecase/LibraryRootManagementUseCase.kt"
        )

        useCasePaths.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import PlaybackManager.",
                !source.contains("import com.viel.aplayer.media.PlaybackManager")
            )
            assertTrue(
                "$relativePath must depend on PlaybackStopper.",
                source.contains("import com.viel.aplayer.application.playback.PlaybackStopper")
            )
        }
    }

    @Test
    fun libraryRootManagementUseCaseKeepsEntityDeletionPrivate() {
        val source = resolveSourceRoot()
            .resolve("application/usecase/LibraryRootManagementUseCase.kt")
            .readText()

        assertTrue(
            "LibraryRootManagementUseCase must not expose a public LibraryRootEntity delete overload.",
            !source.contains("suspend fun deleteLibraryRoot(root: LibraryRootEntity)")
        )
        assertTrue(
            "LibraryRootManagementUseCase must keep root rehydration private.",
            source.contains("private suspend fun findRoot(rootId: String): LibraryRootEntity?")
        )
    }

    @Test
    fun libraryGraphReceivesPlaybackStopperFromMediaGraph() {
        val libraryGraph = resolveSourceRoot().resolve("di/graph/LibraryGraph.kt").readText()

        assertTrue(libraryGraph.contains("playbackStopper = media.playbackStopper"))
        assertTrue(!libraryGraph.contains("playbackManager = data.playbackManager"))
    }

    @Test
    fun managementUseCasesReceiveManualDownloadCleanupGatewayFromDownloadGraph() {
        val sourceRoot = resolveSourceRoot()
        val appContainer = sourceRoot.resolve("AppContainer.kt").readText()
        val libraryGraph = sourceRoot.resolve("di/graph/LibraryGraph.kt").readText()

        assertTrue(appContainer.contains("manualDownloadCleanupGatewayProvider = { download.manualDownloadCleanupGateway }"))
        assertTrue(libraryGraph.contains("LibraryRootManagementUseCase("))
        assertTrue(libraryGraph.contains("BookManagementUseCase("))
        assertTrue(libraryGraph.contains("manualDownloadCleanupGateway = manualDownloadCleanupGatewayProvider()"))
    }

    @Test
    fun absSettingsRootSwitchUsesLibraryRootManagementUseCase() {
        val sourceRoot = resolveSourceRoot()
        val appContainer = sourceRoot.resolve("AppContainer.kt").readText()
        val absSettingsUseCase = sourceRoot.resolve("application/usecase/AbsSettingsConnectionUseCase.kt").readText()

        assertTrue(appContainer.contains("libraryRootManagementUseCase = library.libraryRootManagementUseCase"))
        assertTrue(absSettingsUseCase.contains("libraryRootManagementUseCase.updateAbsLibraryRoot("))
        assertTrue(!absSettingsUseCase.contains("libraryRootGateway.updateAbsLibraryRoot("))
    }

    @Test
    fun playerSceneUsesPlaybackControllerInsteadOfMediaSingletons() {
        val sourceRoot = resolveSourceRoot()
        val guardedUiFiles = listOf(
            "ui/player/PlaybackViewModel.kt",
            "ui/player/BookmarkViewModel.kt",
            "ui/player/PlayerSettingsViewModel.kt",
            "ui/player/MediaPlaybackDelegate.kt",
            "ui/settings/PlayerSettingsManager.kt",
            "ui/settings/SleepTimerManager.kt"
        )

        guardedUiFiles.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import PlaybackManager.",
                !source.contains("import com.viel.aplayer.media.PlaybackManager")
            )
            assertTrue(
                "$relativePath must not import AutoRewindManager.",
                !source.contains("import com.viel.aplayer.media.AutoRewindManager")
            )
            assertTrue(
                "$relativePath must not resolve playback singletons from Context.",
                !source.contains("PlaybackManager.getInstance") &&
                    !source.contains("AutoRewindManager.getInstance")
            )
        }

        val applicationPlaybackFiles = sourceRoot.resolve("application/playback")
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .toList()

        assertTrue(
            "Application playback seam files must exist for player playback wiring.",
            applicationPlaybackFiles.isNotEmpty()
        )
        assertTrue(
            "Application playback seams must not resolve media singletons directly.",
            applicationPlaybackFiles.all { file ->
                val source = file.readText()
                !source.contains("PlaybackManager.getInstance") &&
                    !source.contains("AutoRewindManager.getInstance")
            }
        )

        val dependencies = sourceRoot.resolve("di/dependencies/PresentationDependencies.kt").readText()
        val mediaGraph = sourceRoot.resolve("di/graph/MediaGraph.kt").readText()

        assertTrue(dependencies.contains("val playerPlaybackController: PlayerPlaybackController"))
        assertTrue(mediaGraph.contains("playerPlaybackController: PlayerPlaybackController"))
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for playback lifetime architecture test.")
    }
}
