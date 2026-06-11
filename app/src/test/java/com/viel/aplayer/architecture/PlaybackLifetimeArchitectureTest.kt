package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Playback Lifetime Architecture Test (Locks phase-six graph ownership)
 * Verifies persistence graph and deletion use cases stay decoupled from playback runtime managers.
 */
class PlaybackLifetimeArchitectureTest {

    @Test
    fun dataGraphDoesNotOwnPlaybackRuntimeManagers() {
        val dataGraph = resolveSourceRoot().resolve("graph/DataGraph.kt").readText()

        // Data Graph Persistence Boundary (Forbid media-runtime ownership in the data graph)
        // DataGraph should expose database, settings, and data stores only; playback lifecycle belongs to MediaGraph.
        assertTrue(!dataGraph.contains("PlaybackManager"))
        assertTrue(!dataGraph.contains("AutoRewindManager"))
        assertTrue(!dataGraph.contains("playbackManager"))
        assertTrue(!dataGraph.contains("autoRewindManager"))
    }

    @Test
    fun deletionUseCasesDoNotImportPlaybackManager() {
        val sourceRoot = resolveSourceRoot()
        val useCasePaths = listOf(
            "application/usecase/DeleteBookUseCase.kt",
            "application/usecase/DeleteLibraryRootUseCase.kt"
        )

        useCasePaths.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            // Deletion Playback Seam Boundary (Allow only PlaybackStopper in destructive library workflows)
            // Deletion use cases may coordinate shutdown, but they must not depend on media runtime managers directly.
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
    fun deleteLibraryRootUseCaseKeepsEntityDeletionPrivate() {
        val source = resolveSourceRoot()
            .resolve("application/usecase/DeleteLibraryRootUseCase.kt")
            .readText()

        // Root Deletion Public Seam Guard (Keep presentation callers on root identifiers)
        // DeleteLibraryRootUseCase may resolve the persistence row internally, but it must not expose entity-shaped deletion as a public overload.
        assertTrue(
            "DeleteLibraryRootUseCase must not expose a public LibraryRootEntity invoke overload.",
            !source.contains("suspend fun invoke(root: LibraryRootEntity)")
        )
        assertTrue(
            "DeleteLibraryRootUseCase must keep resolved entity deletion behind a private helper.",
            source.contains("private suspend fun deleteResolvedRoot(root: LibraryRootEntity)")
        )
    }

    @Test
    fun libraryGraphReceivesPlaybackStopperFromMediaGraph() {
        val libraryGraph = resolveSourceRoot().resolve("graph/LibraryGraph.kt").readText()

        // Library Graph Playback Wiring Boundary (Route deletion shutdown through MediaGraph)
        // LibraryGraph should compose deletion use cases with media.playbackStopper instead of data-owned playback managers.
        assertTrue(libraryGraph.contains("playbackStopper = media.playbackStopper"))
        assertTrue(!libraryGraph.contains("playbackManager = data.playbackManager"))
    }

    @Test
    fun playerSceneUsesPlaybackControllerInsteadOfMediaSingletons() {
        val sourceRoot = resolveSourceRoot()
        // Update Playback Lifetime Test (Adapts UI file list to independent ViewModels)
        // Ensures all three split player ViewModels do not import media singletons directly.
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
            // Player Playback Runtime Boundary (Forbid UI helpers from resolving or importing media singletons)
            // Player screens may control playback only through PlayerPlaybackController so MediaGraph remains the singleton owner.
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

        val dependencies = sourceRoot.resolve("dependencies/PresentationDependencies.kt").readText()
        val mediaGraph = sourceRoot.resolve("graph/MediaGraph.kt").readText()

        // Controller Wiring Contract (Keep the player runtime adapter visible at both dependency view and media graph)
        // This guards the intended route: PlayerViewModel -> PlayerScreenDependencies -> MediaGraph adapter -> media managers.
        assertTrue(dependencies.contains("val playerPlaybackController: PlayerPlaybackController"))
        assertTrue(mediaGraph.contains("playerPlaybackController: PlayerPlaybackController"))
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for playback lifetime architecture test.")
    }
}
