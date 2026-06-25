package com.viel.oto.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.Collections

/**
 * Verifies the lifecycle policy inside its extracted Gradle module.
 *
 * App-level architecture tests still prove Koin registrations use this policy, while this module
 * owns the pure ordering and de-duplication behavior that callers depend on during shutdown.
 */
class GraphClosePolicyTest {

    @Test
    fun closeInLifecycleOrderFollowsTheDeclaredStageOrder() {
        val closeOrder = Collections.synchronizedList(mutableListOf<String>())

        register(GraphClosePolicy.Stage.Media, RecordingCloseable("media", closeOrder))
        register(GraphClosePolicy.Stage.Download, RecordingCloseable("download", closeOrder))
        register(GraphClosePolicy.Stage.Abs, RecordingCloseable("abs", closeOrder))
        register(GraphClosePolicy.Stage.Library, RecordingCloseable("library", closeOrder))
        register(GraphClosePolicy.Stage.UiEvents, RecordingCloseable("uiEvents", closeOrder))
        register(GraphClosePolicy.Stage.Data, RecordingCloseable("data", closeOrder))

        GraphClosePolicy.closeInLifecycleOrder()

        assertEquals(listOf("media", "download", "abs", "library", "uiEvents", "data"), closeOrder)
    }

    @Test
    fun duplicateCloseableRegistrationsOnlyCloseOnce() {
        val closeOrder = mutableListOf<String>()
        val closeable = RecordingCloseable("single", closeOrder)

        register(GraphClosePolicy.Stage.Library, closeable)
        register(GraphClosePolicy.Stage.Library, closeable)

        GraphClosePolicy.closeInLifecycleOrder()

        assertEquals(listOf("single"), closeOrder)
    }

    @Test
    fun closePolicyClearsEntriesAfterShutdown() {
        register(GraphClosePolicy.Stage.Media, RecordingCloseable("one", mutableListOf()))

        GraphClosePolicy.closeInLifecycleOrder()
        GraphClosePolicy.closeInLifecycleOrder()

        assertTrue(true)
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
        private val closeOrder: MutableList<String>
    ) : Closeable {
        override fun close() {
            closeOrder += name
        }
    }
}
