package com.viel.aplayer.architecture

import com.viel.aplayer.graph.closeAppGraphsInLifecycleOrder
import com.viel.aplayer.graph.closeInitializedAbsGraphResources
import com.viel.aplayer.graph.closeInitializedLibraryGraphResources
import com.viel.aplayer.graph.closeInitializedUiEventGraphResources
import com.viel.aplayer.graph.releaseInitializedMediaGraphResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Graph Lifecycle Regression Tests (Protects application graph ownership boundaries)
 * Verifies close order and initialized-resource cleanup without instantiating Android-backed graph dependencies.
 */
class GraphLifecycleTest {

    @Test
    fun `app container graph close order should continue through failed graph teardown`() {
        val closeOrder = mutableListOf<String>()

        closeAppGraphsInLifecycleOrder(
            media = RecordingCloseable("media", closeOrder, shouldThrow = true),
            library = RecordingCloseable("library", closeOrder),
            abs = RecordingCloseable("abs", closeOrder),
            uiEvents = RecordingCloseable("uiEvents", closeOrder)
        )

        // Root Graph Failure Isolation (Keeps teardown progressing after media-runtime release fails)
        // The runtime closes first to stop publishers, while ABS, library, and UI event graphs still receive their close callbacks.
        assertEquals(listOf("media", "abs", "library", "uiEvents"), closeOrder)
    }

    @Test
    fun `running abs sync shutdown should keep library dependency open until abs graph closes`() {
        val closeOrder = Collections.synchronizedList(mutableListOf<String>())
        val library = LibraryDependencyCloseable(closeOrder)
        val abs = RunningAbsSyncCloseable(
            closeOrder = closeOrder,
            library = library
        )

        closeAppGraphsInLifecycleOrder(
            media = RecordingCloseable("media", closeOrder),
            library = library,
            abs = abs,
            uiEvents = RecordingCloseable("uiEvents", closeOrder)
        )

        // Active ABS Work Shutdown (Models a sync task observing LibraryGraph state while AbsGraph.close cancels it)
        // The library dependency must still be open during ABS shutdown because AbsGraph injects library-root operations into ABS synchronization.
        assertTrue(abs.libraryWasOpenDuringShutdown)
        assertEquals(listOf("media", "abs", "absSync:libraryOpen", "library", "uiEvents"), closeOrder)
    }

    @Test
    fun `app container close should release initialized media playback runtime once`() {
        val releaseOrder = mutableListOf<String>()
        val initializedPlaybackRuntime = lazy {
            ReleaseRecordingPlaybackRuntime("playbackManager", releaseOrder)
        }
        val media = MediaGraphCloseable(initializedPlaybackRuntime)

        media.playbackManager

        closeAppGraphsInLifecycleOrder(
            media = media,
            library = RecordingCloseable("library", mutableListOf()),
            abs = RecordingCloseable("abs", mutableListOf()),
            uiEvents = RecordingCloseable("uiEvents", mutableListOf())
        )

        // Initialized Playback Release (Models DefaultAppContainer closing a MediaGraph after playback was used)
        // The release hook must run exactly once so PlaybackManager clears controller listeners, polling, and singleton state.
        assertEquals(listOf("playbackManager"), releaseOrder)
    }

    @Test
    fun `app container close should not initialize unused media playback runtime`() {
        val releaseOrder = mutableListOf<String>()
        val unusedPlaybackRuntime = lazy {
            ReleaseRecordingPlaybackRuntime("unusedPlaybackManager", releaseOrder)
        }
        val media = MediaGraphCloseable(unusedPlaybackRuntime)

        closeAppGraphsInLifecycleOrder(
            media = media,
            library = RecordingCloseable("library", mutableListOf()),
            abs = RecordingCloseable("abs", mutableListOf()),
            uiEvents = RecordingCloseable("uiEvents", mutableListOf())
        )

        // Lazy Playback Shutdown Guard (Models closing a container that never touched playback)
        // Shutdown must skip the lazy provider so tests and diagnostics do not allocate a playback runtime only to release it.
        assertFalse(unusedPlaybackRuntime.isInitialized())
        assertTrue(releaseOrder.isEmpty())
    }

    @Test
    fun `library graph close should close only initialized closeable resources and cancel recovery scope`() {
        val closeOrder = mutableListOf<String>()
        val initializedBookQuery = lazy { RecordingCloseable("bookQuery", closeOrder) }
        val uninitializedProgress = lazy { RecordingCloseable("progress", closeOrder) }
        val initializedScan = lazy { RecordingCloseable("scan", closeOrder) }
        val recoveryJob = SupervisorJob()

        initializedBookQuery.value
        initializedScan.value

        closeInitializedLibraryGraphResources(
            closeableResources = listOf(
                initializedBookQuery,
                uninitializedProgress,
                initializedScan
            ),
            recoveryScope = CoroutineScope(recoveryJob)
        )

        // Lazy Ownership Guard (Prevents shutdown from allocating unused graph services)
        // Only the resources resolved by real callers should receive close callbacks during container teardown.
        assertEquals(listOf("bookQuery", "scan"), closeOrder)
        assertFalse(uninitializedProgress.isInitialized())
        assertFalse(recoveryJob.isActive)
    }

    @Test
    fun `abs graph close should skip uninitialized sync coordinator`() {
        val closeOrder = mutableListOf<String>()
        val initializedCoordinator = lazy { RecordingCloseable("absSyncTaskCoordinator", closeOrder) }
        val uninitializedCoordinator = lazy { RecordingCloseable("unusedCoordinator", closeOrder) }

        initializedCoordinator.value

        closeInitializedAbsGraphResources(
            closeableResources = listOf(
                initializedCoordinator,
                uninitializedCoordinator
            )
        )

        // ABS Coordinator Ownership (Keeps remote background scopes tied to actual ABS usage)
        // Unused remote sync infrastructure must not be constructed just because the app container is closing.
        assertEquals(listOf("absSyncTaskCoordinator"), closeOrder)
        assertFalse(uninitializedCoordinator.isInitialized())
    }

    @Test
    fun `ui event graph close should close initialized bridge and cancel bridge scope`() {
        val closeOrder = mutableListOf<String>()
        val initializedBridge = lazy { RecordingCloseable("playbackDomainEventBridge", closeOrder) }
        val uninitializedBridge = lazy { RecordingCloseable("unusedBridge", closeOrder) }
        val eventScopeJob = SupervisorJob()

        initializedBridge.value

        closeInitializedUiEventGraphResources(
            closeableResources = listOf(
                initializedBridge,
                uninitializedBridge
            ),
            eventBridgeScope = CoroutineScope(eventScopeJob)
        )

        // Event Bridge Ownership (Stops application-level event collectors without constructing unused translators)
        // The explicit bridge close happens before the final scope cancellation leak barrier.
        assertEquals(listOf("playbackDomainEventBridge"), closeOrder)
        assertFalse(uninitializedBridge.isInitialized())
        assertFalse(eventScopeJob.isActive)
    }

    @Test
    fun `library graph ownership list should include every closeable gateway declared in source`() {
        val sourceRoot = resolveSourceRoot()
        val libraryGraphSource = sourceRoot.resolve("graph/LibraryGraph.kt").readText()
        val closeSupportSource = sourceRoot.resolve("graph/GraphCloseSupport.kt").readText()

        val declaredCloseableLazyNames = listOf(
            "bookQueryServiceLazy",
            "progressGatewayLazy",
            "scanSchedulerLazy",
            "libraryRootGatewayLazy",
            "searchHistoryGatewayLazy"
        )

        // Library Closeable Gateway Coverage (Locks the graph close ownership list to declared lazy services)
        // Future closeable gateway additions should either join this list or intentionally document a different owner.
        assertTrue(
            declaredCloseableLazyNames.all { lazyName ->
                libraryGraphSource.contains("private val $lazyName") &&
                    libraryGraphSource.contains(lazyName)
            }
        )
        assertTrue(
            "LibraryGraph.close() must delegate initialized lazy resources through closeInitializedLibraryGraphResources(...).",
            libraryGraphSource.contains("closeInitializedLibraryGraphResources(")
        )
        assertTrue(
            "GraphCloseSupport must guard Lazy.isInitialized() before reading Lazy.value.",
            closeSupportSource.contains("if (resource.isInitialized())") &&
                closeSupportSource.contains("resource.value as? Closeable")
        )
    }

    @Test
    fun `media graph close should keep playback manager behind initialized release guard`() {
        val sourceRoot = resolveSourceRoot()
        val mediaGraphSource = sourceRoot.resolve("graph/MediaGraph.kt").readText()

        // Media Graph Playback Teardown Contract (Pin production wiring to the lazy release helper)
        // MediaGraph must expose playback through the same Lazy that close() checks, then call PlaybackManager.release() only inside the initialized guard.
        assertTrue(mediaGraphSource.contains("private val playbackManagerLazy = lazy"))
        assertTrue(mediaGraphSource.contains("val playbackManager: PlaybackManager by playbackManagerLazy"))
        assertTrue(mediaGraphSource.contains("releaseInitializedMediaGraphResource(playbackManagerLazy)"))
        assertTrue(mediaGraphSource.contains("playbackRuntime.release()"))
    }

    private fun resolveSourceRoot(): java.io.File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            java.io.File("src/main/java/com/viel/aplayer"),
            java.io.File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for graph lifecycle test.")
    }

    private class RecordingCloseable(
        private val name: String,
        private val closeOrder: MutableList<String>,
        private val shouldThrow: Boolean = false
    ) : Closeable {
        override fun close() {
            // Close Recording Fixture (Captures teardown order while optionally simulating a failed resource)
            // This lets the lifecycle tests assert root ordering and failure isolation without Android runtime objects.
            closeOrder += name
            if (shouldThrow) {
                error("close failed for $name")
            }
        }
    }

    private class LibraryDependencyCloseable(
        private val closeOrder: MutableList<String>
    ) : Closeable {
        @Volatile
        private var closed = false

        val isOpen: Boolean
            get() = !closed

        override fun close() {
            // Library Dependency Close Fixture (Marks the library graph dependency unavailable)
            // ABS shutdown tests use this state to prove dependent sync work observes LibraryGraph before it is torn down.
            closeOrder += "library"
            closed = true
        }
    }

    private class RunningAbsSyncCloseable(
        private val closeOrder: MutableList<String>,
        private val library: LibraryDependencyCloseable
    ) : Closeable {
        private val shutdownStarted = CountDownLatch(1)
        private val workerObservedLibrary = CountDownLatch(1)

        @Volatile
        var libraryWasOpenDuringShutdown: Boolean = false
            private set

        private val runningSyncWorker = Thread(
            {
                // Running Sync Observation (Waits until AbsGraph.close begins before touching LibraryGraph state)
                // This simulates an in-flight ABS sync task that can still call the injected library-root gateway during shutdown.
                if (shutdownStarted.await(1, TimeUnit.SECONDS)) {
                    libraryWasOpenDuringShutdown = library.isOpen
                    closeOrder += if (libraryWasOpenDuringShutdown) {
                        "absSync:libraryOpen"
                    } else {
                        "absSync:libraryClosed"
                    }
                }
                workerObservedLibrary.countDown()
            },
            "GraphLifecycleTest-AbsSyncWorker"
        )

        init {
            runningSyncWorker.start()
        }

        override fun close() {
            // ABS Sync Shutdown Fixture (Starts cancellation and waits for active sync work to observe dependencies)
            // The lifecycle helper swallows close failures, so the fixture records observable state instead of throwing assertions.
            closeOrder += "abs"
            shutdownStarted.countDown()
            workerObservedLibrary.await(1, TimeUnit.SECONDS)
            runningSyncWorker.join(1_000)
        }
    }

    private class MediaGraphCloseable(
        private val playbackRuntimeLazy: Lazy<ReleaseRecordingPlaybackRuntime>
    ) : Closeable {
        val playbackManager: ReleaseRecordingPlaybackRuntime
            get() = playbackRuntimeLazy.value

        override fun close() {
            // Media Graph Close Fixture (Uses the same lazy release guard as production MediaGraph)
            // This keeps the lifecycle test on the container close seam without constructing Android playback services.
            releaseInitializedMediaGraphResource(playbackRuntimeLazy) { playbackRuntime ->
                playbackRuntime.release()
            }
        }
    }

    private class ReleaseRecordingPlaybackRuntime(
        private val name: String,
        private val releaseOrder: MutableList<String>
    ) {
        fun release() {
            // Playback Release Fixture (Records release-hook execution without Android MediaController dependencies)
            // The fake keeps the test focused on lazy lifecycle ownership rather than media runtime internals.
            releaseOrder += name
        }
    }
}
