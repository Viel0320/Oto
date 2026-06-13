package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Playback Dependency View Architecture Rule (Protects narrowed playback container surfaces)
 * Locks playback runtime, player UI, VFS data-source, and recovery callers onto typed dependency views before a future state machine extraction.
 */
class PlaybackDependencyViewArchitectureTest {

    @Test
    fun playbackProvidersReturnTypedViewsInsteadOfFullContainer() {
        val sourceRoot = resolveSourceRoot()
        val applicationSource = sourceRoot.resolve("APlayerApplication.kt").readText()

        val expectedProviders = mapOf(
            "getPlaybackRuntimeDependencies" to "PlaybackRuntimeDependencies",
            "getPlaybackRecoveryDependencies" to "PlaybackRecoveryDependencies",
            "getVfsPlaybackDependencies" to "VfsPlaybackDependencies",
            "getPlayerScreenDependencies" to "PlayerScreenDependencies"
        )

        expectedProviders.forEach { (providerName, returnType) ->
            // Typed Provider Contract (Keeps dependency narrowing visible at the application boundary)
            // Each provider may be backed by AppContainer internally, but callers receive only the view they need.
            assertTrue(
                "$providerName must return $returnType.",
                applicationSource.contains("fun $providerName(context: Context): $returnType")
            )
        }
    }

    @Test
    fun playbackCallersUseTheirNarrowDependencyProviders() {
        val sourceRoot = resolveSourceRoot()
        val expectedCalls = mapOf(
            "media/PlaybackManager.kt" to "getPlaybackRuntimeDependencies",
            "media/service/PlaybackService.kt" to "getPlaybackRuntimeDependencies",
            "media/VfsPlaybackDataSource.kt" to "getVfsPlaybackDependencies",
            "media/AutoRewindManager.kt" to "getPlaybackRecoveryDependencies",
            // Update Dependency View Test (Adapts dependency view mapping to separate ViewModels)
            // Ensures PlaybackViewModel, BookmarkViewModel, and PlayerSettingsViewModel use their respective screen dependencies.
            "ui/player/PlaybackViewModel.kt" to "getPlayerScreenDependencies",
            "ui/player/BookmarkViewModel.kt" to "getPlayerScreenDependencies",
            "ui/player/PlayerSettingsViewModel.kt" to "getPlayerScreenDependencies"
        )

        expectedCalls.forEach { (relativePath, providerName) ->
            val source = sourceRoot.resolve(relativePath).readText()
            // Playback Caller Boundary (Verifies current callers stay on their intended dependency views)
            // This gives widget, notification, service, manager, and UI paths one shared fact about allowed dependency access.
            assertTrue(
                "$relativePath must use $providerName instead of the full container.",
                source.contains(providerName)
            )
        }
    }

    @Test
    fun playbackRuntimeViewDoesNotExposeScreenOrScannerSurfaces() {
        val sourceRoot = resolveSourceRoot()
        // Title: Update di path in test (Point to application/di/dependencies/PlaybackDependencies.kt)
        // Changes relative path to target the dependencies subdirectory under application/di.
        val playbackDependencies = sourceRoot.resolve("application/di/dependencies/PlaybackDependencies.kt").readText().replace("\r\n", "\n")
        val runtimeInterface = playbackDependencies.substringAfter("interface PlaybackRuntimeDependencies")
            .substringBefore("/**\n * VFS Playback Dependencies")
        val vfsInterface = playbackDependencies.substringAfter("interface VfsPlaybackDependencies")

        // Runtime View Surface Guard (Prevents playback core from growing back into a UI or scan facade)
        // Playback runtime may read plans, progress, availability, ABS sessions, and events, but not broad library UI or scanner APIs.
        assertTrue(!runtimeInterface.contains("LibraryFacade"))
        assertTrue(!runtimeInterface.contains("ScanScheduler"))
        assertTrue(!runtimeInterface.contains("AppEventSink"))

        // VFS View Surface Guard (Keeps data-source reads separate from playback session and UI state)
        // Data sources need file lookup and VFS reads only, not foreground playback commands or screen feedback.
        assertTrue(!vfsInterface.contains("PlaybackRuntimeDependencies"))
        assertTrue(!vfsInterface.contains("PlayerScreenDependencies"))
        assertTrue(!vfsInterface.contains("LibraryFacade"))
    }

    @Test
    fun bookDaoDoesNotImportMediaRuntime() {
        val sourceRoot = resolveSourceRoot()
        val bookDaoSource = sourceRoot.resolve("data/dao/BookDao.kt").readText()

        // DAO Timeline Mapping Boundary (Keeps Room persistence out of the media runtime package)
        // BookDao may use neutral timeline math for progress coordinates, but it must not import playback/session/runtime media classes.
        assertTrue(!bookDaoSource.contains("import com.viel.aplayer.media."))
        assertTrue(bookDaoSource.contains("import com.viel.aplayer.timeline.PositionMapper"))
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for playback dependency architecture test.")
    }
}
