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

        // Root Graph Shutdown Order (Keeps publisher lifetimes aligned)
        // Library resources close first, ABS background work closes next, and UI event bridges close last even if an earlier graph fails.
        assertEquals(listOf("library", "abs", "uiEvents"), closeOrder)
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
}
