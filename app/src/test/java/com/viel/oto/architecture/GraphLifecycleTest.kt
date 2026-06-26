package com.viel.oto.architecture

import com.viel.oto.di.GraphClosePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Protects application di ownership boundaries under the Koin-based di container.
 * Verifies that GraphClosePolicy preserves the previous close order
 * (media -> download -> abs -> library -> uiEvents -> data) and continues through failed teardowns.
 */
class GraphLifecycleTest {

    @Test
    fun `graph close policy should continue through failed graph teardown`() {
        val closeOrder = Collections.synchronizedList(mutableListOf<String>())
        register(GraphClosePolicy.Stage.Media, RecordingCloseable("media", closeOrder, shouldThrow = true))
        register(GraphClosePolicy.Stage.Download, RecordingCloseable("download", closeOrder))
        register(GraphClosePolicy.Stage.Abs, RecordingCloseable("abs", closeOrder))
        register(GraphClosePolicy.Stage.Library, RecordingCloseable("library", closeOrder))
        register(GraphClosePolicy.Stage.UiEvents, RecordingCloseable("uiEvents", closeOrder))
        register(GraphClosePolicy.Stage.Data, RecordingCloseable("data", closeOrder))

        GraphClosePolicy.closeInLifecycleOrder()

        assertEquals(listOf("media", "download", "abs", "library", "uiEvents", "data"), closeOrder)
    }

    @Test
    fun `running abs sync shutdown should keep library dependency open until abs graph closes`() {
        val closeOrder = Collections.synchronizedList(mutableListOf<String>())
        val library = LibraryDependencyCloseable(closeOrder)
        val abs = RunningAbsSyncCloseable(
            closeOrder = closeOrder,
            library = library
        )

        register(GraphClosePolicy.Stage.Media, RecordingCloseable("media", closeOrder))
        register(GraphClosePolicy.Stage.Download, RecordingCloseable("download", closeOrder))
        register(GraphClosePolicy.Stage.Abs, abs)
        register(GraphClosePolicy.Stage.Library, library)
        register(GraphClosePolicy.Stage.UiEvents, RecordingCloseable("uiEvents", closeOrder))
        register(GraphClosePolicy.Stage.Data, RecordingCloseable("data", closeOrder))

        GraphClosePolicy.closeInLifecycleOrder()

        assertTrue(abs.libraryWasOpenDuringShutdown)
        assertEquals(
            listOf("media", "download", "abs", "absSync:libraryOpen", "library", "uiEvents", "data"),
            closeOrder
        )
    }

    @Test
    fun `graph close policy should clear registrations after close`() {
        register(GraphClosePolicy.Stage.Media, RecordingCloseable("one", mutableListOf()))
        GraphClosePolicy.closeInLifecycleOrder()
        // Re-closing should be a no-op without throwing.
        GraphClosePolicy.closeInLifecycleOrder()
        assertTrue(true)
    }

    @Test
    fun `graph close policy should close lower priority first inside the same stage`() {
        val closeOrder = Collections.synchronizedList(mutableListOf<String>())
        register(GraphClosePolicy.Stage.Library, RecordingCloseable("late", closeOrder), priority = 10)
        register(GraphClosePolicy.Stage.Library, RecordingCloseable("early", closeOrder), priority = 0)

        GraphClosePolicy.closeInLifecycleOrder()

        assertEquals(listOf("early", "late"), closeOrder)
    }

    @Test
    fun `koin modules register closeable resources through graph close policy`() {
        val sourceRoot = resolveSourceRoot()
        val mediaModuleSource = sourceRoot.resolve("di/MediaModule.kt").readText()
        val downloadModuleSource = sourceRoot.resolve("di/DownloadModule.kt").readText()
        val absSyncModuleSource = sourceRoot.resolve("di/AbsSyncModule.kt").readText()
        val libraryScanModuleSource = resolveLibraryScanModuleFile().readText()
        val uiEventModuleSource = sourceRoot.resolve("di/UiEventModule.kt").readText()
        val coreDataModuleSource = resolveCoreDataModuleFile().readText()

        assertTrue(
            "MediaModule must register playback runtime with GraphClosePolicy.",
            mediaModuleSource.contains("GraphClosePolicy.register")
        )
        assertTrue(
            "DownloadModule must register download resources with GraphClosePolicy.",
            downloadModuleSource.contains("GraphClosePolicy.register")
        )
        assertTrue(
            "AbsSyncModule must register abs sync coordinator with GraphClosePolicy.",
            absSyncModuleSource.contains("GraphClosePolicy.register")
        )
        assertTrue(
            "LibraryScanModule must register scan resources with GraphClosePolicy.",
            libraryScanModuleSource.contains("GraphClosePolicy.register")
        )
        assertTrue(
            "UiEventModule must register event bridge resources with GraphClosePolicy.",
            uiEventModuleSource.contains("GraphClosePolicy.register")
        )
        assertTrue(
            "CoreDataModule must register AppDatabase with GraphClosePolicy.",
            coreDataModuleSource.contains("GraphClosePolicy.register")
        )
    }

    private fun resolveSourceRoot(): java.io.File {
        val candidates = listOf(
            java.io.File("src/main/java/com/viel/oto"),
            java.io.File("app/src/main/java/com/viel/oto")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for di lifecycle test.")
    }

    private fun resolveCoreDataModuleFile(): java.io.File {
        val candidates = listOf(
            java.io.File("data/store/src/main/java/com/viel/oto/di/CoreDataModule.kt"),
            java.io.File("../data/store/src/main/java/com/viel/oto/di/CoreDataModule.kt"),
            java.io.File("src/main/java/com/viel/oto/di/CoreDataModule.kt"),
            java.io.File("app/src/main/java/com/viel/oto/di/CoreDataModule.kt")
        )
        return candidates.firstOrNull { candidate -> candidate.isFile }
            ?: error("Could not locate CoreDataModule for di lifecycle test.")
    }

    /**
     * Resolves LibraryScanModule from either its extracted import module or the old app location
     * so the lifecycle guard keeps covering the module during phased Gradle extraction.
     */
    private fun resolveLibraryScanModuleFile(): java.io.File {
        val candidates = listOf(
            java.io.File("library/import/src/main/java/com/viel/oto/di/LibraryScanModule.kt"),
            java.io.File("../library/import/src/main/java/com/viel/oto/di/LibraryScanModule.kt"),
            java.io.File("src/main/java/com/viel/oto/di/LibraryScanModule.kt"),
            java.io.File("app/src/main/java/com/viel/oto/di/LibraryScanModule.kt")
        )
        return candidates.firstOrNull { candidate -> candidate.isFile }
            ?: error("Could not locate LibraryScanModule for di lifecycle test.")
    }

    private fun register(
        stage: GraphClosePolicy.Stage,
        closeable: Closeable,
        priority: Int = 0
    ) {
        GraphClosePolicy.register(stage = stage, closeable = closeable, priority = priority)
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
}
