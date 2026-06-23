package com.viel.aplayer.di.koin

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the ordered di shutdown policy previously held by `closeAppGraphsInLifecycleOrder`.
 *
 * Koin's GlobalContext.close() does not guarantee a deterministic close order, so closeable
 * resources register themselves when they are actually created and are released explicitly before stopKoin().
 * The stage and priority model preserves the old graph order without constructing lazy resources during shutdown.
 */
internal object GraphClosePolicy {

    enum class Stage {
        Media,
        Download,
        Abs,
        Library,
        UiEvents
    }

    private data class Entry(
        val stage: Stage,
        val priority: Int,
        val closeable: Closeable
    )

    private val entries: MutableList<Entry> = CopyOnWriteArrayList()

    /**
     * Register a closeable resource to be released during app shutdown without forcing other Koin definitions to initialize.
     * Lower priority values close first inside the same lifecycle stage.
     */
    fun register(stage: Stage, closeable: Closeable, priority: Int = 0) {
        if (entries.none { it.closeable === closeable }) {
            entries.add(Entry(stage = stage, priority = priority, closeable = closeable))
        }
    }

    /**
     * Release every initialized closeable in lifecycle order, swallowing individual failures
     * so one broken teardown does not skip the remaining resources.
     */
    fun closeInLifecycleOrder() {
        Stage.entries.forEach { stage ->
            entries
                .filter { it.stage == stage }
                .sortedBy { it.priority }
                .forEach { entry ->
                    runCatching { entry.closeable.close() }
                }
        }
        entries.clear()
    }
}
