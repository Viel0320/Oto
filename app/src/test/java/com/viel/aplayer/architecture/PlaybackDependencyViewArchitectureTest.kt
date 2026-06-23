package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Protects narrowed playback container surfaces.
 * Locks playback runtime, player UI, VFS data-source, and recovery callers onto typed dependency views before a future state machine extraction.
 */
class PlaybackDependencyViewArchitectureTest {

    @Test
    fun playbackProvidersReturnTypedViewsInsteadOfFullContainer() {
        val sourceRoot = resolveSourceRoot()
        val dependencyViewModule = sourceRoot.resolve("di/koin/DependencyViewModule.kt").readText()

        val expectedBindings = listOf(
            "PlaybackRuntimeDependencies",
            "PlaybackRecoveryDependencies",
            "VfsPlaybackDependencies",
            "PlayerScreenDependencies"
        )

        expectedBindings.forEach { bindingName ->
            assertTrue(
                "DependencyViewModule must bind $bindingName.",
                dependencyViewModule.contains("single<$bindingName>")
            )
        }
    }

    @Test
    fun playbackCallersUseTheirNarrowDependencyProviders() {
        val sourceRoot = resolveSourceRoot()
        val expectedCalls = mapOf(
            "media/PlaybackManager.kt" to "PlaybackRuntimeDependencies",
            "media/service/PlaybackService.kt" to "PlaybackRuntimeDependencies",
            "media/VfsPlaybackDataSource.kt" to "VfsPlaybackDependencies",
            "media/AutoRewindManager.kt" to "PlaybackRecoveryDependencies",
            "ui/player/PlaybackViewModel.kt" to "PlayerScreenDependencies",
            "ui/player/BookmarkViewModel.kt" to "PlayerScreenDependencies",
            "ui/player/PlayerSettingsViewModel.kt" to "PlayerScreenDependencies"
        )

        expectedCalls.forEach { (relativePath, dependencyView) ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must use $dependencyView instead of the full container.",
                source.contains(dependencyView)
            )
        }
    }

    @Test
    fun playbackRuntimeViewDoesNotExposeScreenOrScannerSurfaces() {
        val sourceRoot = resolveSourceRoot()
        val playbackDependencies = sourceRoot.resolve("di/dependencies/PlaybackDependencies.kt").readText().replace("\r\n", "\n")
        val runtimeInterface = playbackDependencies.substringAfter("interface PlaybackRuntimeDependencies")
            .substringBefore("/**\n * VFS Playback Dependencies")
        val vfsInterface = playbackDependencies.substringAfter("interface VfsPlaybackDependencies")

        assertTrue(!runtimeInterface.contains("LibraryFacade"))
        assertTrue(!runtimeInterface.contains("ScanScheduler"))
        assertTrue(!runtimeInterface.contains("AppEventSink"))

        assertTrue(!vfsInterface.contains("PlaybackRuntimeDependencies"))
        assertTrue(!vfsInterface.contains("PlayerScreenDependencies"))
        assertTrue(!vfsInterface.contains("LibraryFacade"))
    }

    @Test
    fun bookDaoDoesNotImportMediaRuntime() {
        val sourceRoot = resolveSourceRoot()
        val bookDaoSource = sourceRoot.resolve("data/dao/BookDao.kt").readText()

        assertTrue(!bookDaoSource.contains("import com.viel.aplayer.media."))
        assertTrue(bookDaoSource.contains("import com.viel.aplayer.timeline.PositionMapper"))
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for playback dependency architecture test.")
    }
}
