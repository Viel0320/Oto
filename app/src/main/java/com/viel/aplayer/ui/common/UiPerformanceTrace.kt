package com.viel.aplayer.ui.common

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import com.viel.aplayer.logger.UiPerformanceLogger

private const val EVENT_SAMPLE_WINDOW_MS = 1_000L
private const val DRAW_SAMPLE_WINDOW_MS = 1_000L
private const val DRAW_CLOCK_CHECK_INTERVAL = 16

/**
 * UI Performance Trace Modifier (Attach node/count/route/state diagnostics to page boundaries)
 *
 * The modifier records sampled recomposition commits, layout passes, and draw windows for a single
 * top-level UI node. Counters and latest route fields are plain remembered fields instead of Compose
 * state so diagnostic updates do not trigger the very recompositions or redraws they are measuring.
 */
fun Modifier.uiPerformanceTrace(
    node: String,
    route: String,
    state: String
): Modifier = composed {
    val counters = remember {
        val nowMs = UiPerformanceLogger.nowMs()
        UiPerformanceTraceCounters(
            lastRoute = route,
            lastState = state,
            recomposeWindowStartedAtMs = nowMs,
            layoutWindowStartedAtMs = nowMs,
            drawWindowStartedAtMs = nowMs
        )
    }

    SideEffect {
        counters.lastRoute = route
        counters.lastState = state
        counters.recomposeCount += 1

        val nowMs = UiPerformanceLogger.nowMs()
        if (counters.recomposeCount == 1 || nowMs - counters.recomposeWindowStartedAtMs >= EVENT_SAMPLE_WINDOW_MS) {
            UiPerformanceLogger.logRecompose(
                node = node,
                count = counters.recomposeCount,
                route = counters.lastRoute,
                state = counters.lastState
            )
            counters.recomposeWindowStartedAtMs = nowMs
        }
    }

    onGloballyPositioned { coordinates ->
        val size = coordinates.size
        counters.layoutCount += 1
        val sizeChanged = counters.lastSize != size
        counters.lastSize = size

        val nowMs = UiPerformanceLogger.nowMs()
        if (counters.layoutCount == 1 || sizeChanged || nowMs - counters.layoutWindowStartedAtMs >= EVENT_SAMPLE_WINDOW_MS) {
            UiPerformanceLogger.logLayout(
                node = node,
                count = counters.layoutCount,
                route = counters.lastRoute,
                state = counters.lastState,
                width = size.width,
                height = size.height,
                sizeChanged = sizeChanged
            )
            counters.layoutWindowStartedAtMs = nowMs
        }
    }.drawWithContent {
        drawContent()
        counters.drawCount += 1
        counters.windowDrawCount += 1

        if (counters.drawCount % DRAW_CLOCK_CHECK_INTERVAL == 0) {
            val nowMs = UiPerformanceLogger.nowMs()
            val windowMs = nowMs - counters.drawWindowStartedAtMs
            if (windowMs >= DRAW_SAMPLE_WINDOW_MS) {
                UiPerformanceLogger.logDrawWindow(
                    node = node,
                    count = counters.drawCount,
                    route = counters.lastRoute,
                    state = counters.lastState,
                    windowDraws = counters.windowDrawCount,
                    windowMs = windowMs
                )
                counters.windowDrawCount = 0
                counters.drawWindowStartedAtMs = nowMs
            }
        }
    }
}

/**
 * UI Performance Trace Counters (Mutable diagnostic state outside Compose snapshots)
 *
 * Keeping these fields outside mutableState prevents trace bookkeeping from invalidating the traced
 * node and makes count increments reflect only real UI work produced by the page boundary.
 */
private class UiPerformanceTraceCounters(
    var recomposeCount: Int = 0,
    var layoutCount: Int = 0,
    var drawCount: Int = 0,
    var windowDrawCount: Int = 0,
    var recomposeWindowStartedAtMs: Long = 0L,
    var layoutWindowStartedAtMs: Long = 0L,
    var drawWindowStartedAtMs: Long = 0L,
    var lastSize: IntSize = IntSize.Zero,
    var lastRoute: String,
    var lastState: String
)
