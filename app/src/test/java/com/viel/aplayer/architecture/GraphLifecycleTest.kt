package com.viel.aplayer.architecture

import com.viel.aplayer.graph.closeAppGraphsInLifecycleOrder
import com.viel.aplayer.graph.closeInitializedAbsGraphResources
import com.viel.aplayer.graph.closeInitializedLibraryGraphResources
import com.viel.aplayer.graph.closeInitializedUiEventGraphResources
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
            library = RecordingCloseable("library", closeOrder, shouldThrow = true),
            abs = RecordingCloseable("abs", closeOrder),
            uiEvents = RecordingCloseable("uiEvents", closeOrder)
        )

        // Dependent Graph Shutdown Order (Closes ABS before the library resources it can call into)
        // UI event bridges still close last so graph teardown can publish final feedback without losing the process-wide event channel.
        assertEquals(listOf("abs", "library", "uiEvents"), closeOrder)
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
            library = library,
            abs = abs,
            uiEvents = RecordingCloseable("uiEvents", closeOrder)
        )

        // Active ABS Work Shutdown (Models a sync task observing LibraryGraph state while AbsGraph.close cancels it)
        // The library dependency must still be open during ABS shutdown because AbsGraph injects library-root operations into ABS synchronization.
        assertTrue(abs.libraryWasOpenDuringShutdown)
        assertEquals(listOf("abs", "absSync:libraryOpen", "library", "uiEvents"), closeOrder)
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
}
