package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

/**
 * UI Performance Logger (Records Compose boundary churn with stable field names)
 *
 * This logger intentionally keeps the schema flat as event, node, count, route, and state so Logcat
 * filters can compare root and page-level recomposition, layout, and continuous draw activity
 * without coupling UI diagnostics to feature ViewModels or rendering internals.
 */
internal object UiPerformanceLogger {
    private const val TAG = "UiPerformance"

    /**
     * Elapsed Milliseconds (Expose one clock source for UI trace counters)
     *
     * Trace modifiers use this monotonic clock to bucket draw bursts without depending on wall-clock
     * time, which keeps diagnostics stable across device sleep, time changes, and locale changes.
     */
    fun nowMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Log Recomposition Count (Track how often a page boundary commits)
     *
     * The count field is cumulative for the instrumented node instance, while route and state carry
     * only sanitized presentation facts such as visibility flags, tab names, and collection sizes.
     */
    fun logRecompose(node: String, count: Int, route: String, state: String) {
        Log.d(TAG, format(event = "recompose", node = node, count = count, route = route, state = state))
    }

    /**
     * Log Layout Count (Track page boundary measurement and placement churn)
     *
     * Width, height, and sizeChanged are emitted as extra fields so a stable count increase can be
     * separated from actual bounds changes when investigating layout invalidation loops.
     */
    fun logLayout(
        node: String,
        count: Int,
        route: String,
        state: String,
        width: Int,
        height: Int,
        sizeChanged: Boolean
    ) {
        Log.d(
            TAG,
            format(
                event = "layout",
                node = node,
                count = count,
                route = route,
                state = state,
                extra = "width=$width height=$height sizeChanged=$sizeChanged"
            )
        )
    }

    /**
     * Log Draw Window Count (Track sustained drawing without logging every frame)
     *
     * The count field remains the cumulative draw count for the node, while windowDraws reports how
     * many draw passes occurred inside the sampled window to identify continuous rendering surfaces.
     */
    fun logDrawWindow(
        node: String,
        count: Int,
        route: String,
        state: String,
        windowDraws: Int,
        windowMs: Long
    ) {
        Log.d(
            TAG,
            format(
                event = "draw",
                node = node,
                count = count,
                route = route,
                state = state,
                extra = "windowDraws=$windowDraws windowMs=$windowMs"
            )
        )
    }

    private fun format(
        event: String,
        node: String,
        count: Int,
        route: String,
        state: String,
        extra: String = ""
    ): String {
        val base = "event=$event node=$node count=$count route=$route state=$state"
        return if (extra.isBlank()) base else "$base $extra"
    }
}
