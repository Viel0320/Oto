package com.viel.oto.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

class TransitionGate<T>(
    private val execute: (T) -> Boolean
) {
    private var isTransitionIdle = true
    private var pendingRequest: PendingTransitionRequest<T>? = null

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
            isTransitionIdle = false
        }
    }

    private data class PendingTransitionRequest<T>(
        val request: T,
        val beforeExecute: () -> Unit
    )
}

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
