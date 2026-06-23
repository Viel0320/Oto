package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks di ownership under the Koin-based container.
 * Verifies persistence di and deletion use cases stay decoupled from playback runtime managers,
 * and that the player scene keeps using the playback controller seam instead of media singletons.
 */
class PlaybackLifetimeArchitectureTest {

    @Test
    fun coreDataModuleDoesNotOwnPlaybackRuntimeManagers() {
        val coreDataModule = resolveSourceRoot().resolve("di/koin/CoreDataModule.kt").readText()

        assertTrue(!coreDataModule.contains("PlaybackManager"))
        assertTrue(!coreDataModule.contains("AutoRewindManager"))
        assertTrue(!coreDataModule.contains("playbackManager"))
        assertTrue(!coreDataModule.contains("autoRewindManager"))
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
    fun libraryUseCaseModuleReceivesPlaybackStopperFromMediaModule() {
        val libraryUseCaseModule = resolveSourceRoot().resolve("di/koin/LibraryUseCaseModule.kt").readText()

        assertTrue(libraryUseCaseModule.contains("playbackStopper = get<PlaybackStopper>()"))
        assertTrue(!libraryUseCaseModule.contains("playbackManager = data.playbackManager"))
    }

    @Test
    fun managementUseCasesReceiveManualDownloadCleanupGatewayFromDownloadModule() {
        val sourceRoot = resolveSourceRoot()
        val libraryUseCaseModule = sourceRoot.resolve("di/koin/LibraryUseCaseModule.kt").readText()

        assertTrue(libraryUseCaseModule.contains("LibraryRootManagementUseCase("))
        assertTrue(libraryUseCaseModule.contains("BookManagementUseCase("))
        assertTrue(libraryUseCaseModule.contains("manualDownloadCleanupGateway = get<ManualDownloadCleanupGateway>()"))
    }

    @Test
    fun absSettingsRootSwitchUsesLibraryRootManagementUseCase() {
        val sourceRoot = resolveSourceRoot()
        val settingsUseCaseModule = sourceRoot.resolve("di/koin/SettingsUseCaseModule.kt").readText()
        val absSettingsUseCase = sourceRoot.resolve("application/usecase/AbsSettingsConnectionUseCase.kt").readText()

        assertTrue(settingsUseCaseModule.contains("libraryRootManagementUseCase = get()"))
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

        val mediaPlaybackControllerModule = sourceRoot.resolve("di/koin/MediaPlaybackControllerModule.kt").readText()

        assertTrue(mediaPlaybackControllerModule.contains("PlayerPlaybackController"))
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
