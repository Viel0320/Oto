package com.viel.aplayer.architecture

import com.viel.aplayer.di.graph.closeAppGraphsInLifecycleOrder
import com.viel.aplayer.di.graph.closeInitializedAbsGraphResources
import com.viel.aplayer.di.graph.closeInitializedLibraryGraphResources
import com.viel.aplayer.di.graph.closeInitializedUiEventGraphResources
import com.viel.aplayer.di.graph.releaseInitializedMediaGraphResource
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
 * Protects application di ownership boundaries.
 * Verifies close order and initialized-resource cleanup without instantiating Android-backed di dependencies.
 */
class GraphLifecycleTest {

    @Test
    fun `app container graph close order should continue through failed graph teardown`() {
        val closeOrder = mutableListOf<String>()

        closeAppGraphsInLifecycleOrder(
            media = RecordingCloseable("media", closeOrder, shouldThrow = true),
            download = RecordingCloseable("download", closeOrder),
            library = RecordingCloseable("library", closeOrder),
            abs = RecordingCloseable("abs", closeOrder),
            uiEvents = RecordingCloseable("uiEvents", closeOrder)
        )

        assertEquals(listOf("media", "download", "abs", "library", "uiEvents"), closeOrder)
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
            download = RecordingCloseable("download", closeOrder),
            library = library,
            abs = abs,
            uiEvents = RecordingCloseable("uiEvents", closeOrder)
        )

        assertTrue(abs.libraryWasOpenDuringShutdown)
        assertEquals(listOf("media", "download", "abs", "absSync:libraryOpen", "library", "uiEvents"), closeOrder)
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
            download = RecordingCloseable("download", mutableListOf()),
            library = RecordingCloseable("library", mutableListOf()),
            abs = RecordingCloseable("abs", mutableListOf()),
            uiEvents = RecordingCloseable("uiEvents", mutableListOf())
        )

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
            download = RecordingCloseable("download", mutableListOf()),
            library = RecordingCloseable("library", mutableListOf()),
            abs = RecordingCloseable("abs", mutableListOf()),
            uiEvents = RecordingCloseable("uiEvents", mutableListOf())
        )

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

        assertEquals(listOf("playbackDomainEventBridge"), closeOrder)
        assertFalse(uninitializedBridge.isInitialized())
        assertFalse(eventScopeJob.isActive)
    }

    @Test
    fun `library graph ownership list should include every closeable gateway declared in source`() {
        val sourceRoot = resolveSourceRoot()
        val libraryGraphSource = sourceRoot.resolve("di/graph/LibraryGraph.kt").readText()
        val closeSupportSource = sourceRoot.resolve("di/graph/GraphCloseSupport.kt").readText()

        val declaredCloseableLazyNames = listOf(
            "bookMetadataGatewayLazy",
            "chapterGatewayLazy",
            "progressGatewayLazy",
            "scanSchedulerLazy",
            "libraryRootGatewayLazy",
            "searchHistoryGatewayLazy"
        )

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
        val mediaGraphSource = sourceRoot.resolve("di/graph/MediaGraph.kt").readText()

        assertTrue(mediaGraphSource.contains("private val playbackManagerLazy = lazy"))
        assertTrue(mediaGraphSource.contains("val playbackManager: PlaybackManager by playbackManagerLazy"))
        assertTrue(mediaGraphSource.contains("releaseInitializedMediaGraphResource(playbackManagerLazy)"))
        assertTrue(mediaGraphSource.contains("playbackRuntime.release()"))
    }

    private fun resolveSourceRoot(): java.io.File {
        val candidates = listOf(
            java.io.File("src/main/java/com/viel/aplayer"),
            java.io.File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for di lifecycle test.")
    }

    private class RecordingCloseable(
        private val name: String,
        private val closeOrder: MutableList<String>,
        private val shouldThrow: Boolean = false
    ) : Closeable {
        override fun close() {
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
            releaseOrder += name
        }
    }
}
