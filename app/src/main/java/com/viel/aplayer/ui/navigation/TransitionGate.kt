package com.viel.aplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/*
 * Transition Gate (Generic visual-transition request gate)
 *
 * Queues the newest request while a visual transition is busy, then flushes it after the
 * owning overlay reports idle; callers provide the domain-specific execution adapter.
 */
class TransitionGate<T>(
    private val execute: (T) -> Boolean
) {
    private var isTransitionIdle = true
    private var pendingRequest: PendingTransitionRequest<T>? = null

    /*
     * Request Transition Work (Gate rapid retargeting)
     *
     * Executes immediately only when the transition is idle; otherwise stores the newest request
     * so the previous shared-element or overlay chain can finish before the next target appears.
     */
    fun request(
        request: T,
        beforeExecute: () -> Unit = {}
    ) {
        val nextRequest = PendingTransitionRequest(
            request = request,
            beforeExecute = beforeExecute
        )
        if (isTransitionIdle) {
            executeNow(nextRequest)
        } else {
            pendingRequest = nextRequest
        }
    }

    /*
     * Transition Idle Update (Flush queued work after visual completion)
     *
     * Receives lifecycle updates from the owner animation and replays a queued request once the
     * transition becomes idle, keeping source and target lifetimes ordered by visual completion.
     */
    fun onTransitionIdleChanged(isIdle: Boolean) {
        isTransitionIdle = isIdle
        if (isIdle) {
            pendingRequest?.let { queuedRequest ->
                pendingRequest = null
                executeNow(queuedRequest)
            }
        }
    }

    private fun executeNow(queuedRequest: PendingTransitionRequest<T>) {
        queuedRequest.beforeExecute()
        if (execute(queuedRequest.request)) {
            /*
             * Immediate Busy Mark (Close the same-frame retarget window)
             *
             * Some animations report busy after recomposition, so an execution adapter can return
             * true when it starts a transition and the gate should block rapid follow-up requests.
             */
            isTransitionIdle = false
        }
    }

    /*
     * Pending Transition Request (Retain source-side effects until execution)
     *
     * Stores both the request payload and pre-execution source cleanup, such as closing Search,
     * so queued requests keep their source content composed until the actual handoff starts.
     */
    private data class PendingTransitionRequest<T>(
        val request: T,
        val beforeExecute: () -> Unit
    )
}

/*
 * Remember Transition Gate (Compose lifecycle factory)
 *
 * Keeps one gate instance for the supplied owner keys while allowing the execution adapter to
 * observe fresh Compose state, preserving locality for the caller-specific transition policy.
 */
@Composable
fun <T> rememberTransitionGate(
    vararg keys: Any?,
    execute: (T) -> Boolean
): TransitionGate<T> {
    val latestExecute = rememberUpdatedState(execute)
    return remember(*keys) {
        TransitionGate { request ->
            latestExecute.value(request)
        }
    }
}
