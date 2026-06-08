package com.viel.aplayer.graph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.Closeable

/**
 * App Graph Teardown Order (Centralizes root graph shutdown policy)
 * Closes dependent ABS work before local library resources, then cancels UI event bridges after all graph publishers are stopped.
 */
internal fun closeAppGraphsInLifecycleOrder(
    library: Closeable,
    abs: Closeable,
    uiEvents: Closeable
) {
    // Dependent Graph Shutdown (Close ABS before the library resources it can call into)
    // ABS sync coordination receives library-root gateway operations, so active ABS cancellation must observe live library dependencies.
    runCatching { abs.close() }
    runCatching { library.close() }
    runCatching { uiEvents.close() }
}

/**
 * Library Graph Resource Teardown (Closes only runtime-created library resources)
 * Avoids constructing lazy services during shutdown while still cancelling every initialized Closeable and recovery coroutine owner.
 */
internal fun closeInitializedLibraryGraphResources(
    closeableResources: List<Lazy<*>>,
    recoveryScope: CoroutineScope?
) {
    closeableResources.forEach { resource ->
        // Initialized Resource Guard (Prevents teardown from creating new services)
        // Only resources touched during normal runtime can own active jobs, so uninitialized lazy providers stay untouched.
        if (resource.isInitialized()) {
            runCatching { (resource.value as? Closeable)?.close() }
        }
    }
    // Recovery Scope Cancellation (Stops background cover repair after dependent services have shut down)
    // The scope is optional because cover recovery is allocated lazily only when a caller needs it.
    recoveryScope?.coroutineContext?.get(Job)?.cancel()
}

/**
 * ABS Graph Resource Teardown (Closes initialized remote background coordinators)
 * Keeps ABS shutdown deterministic without allocating catalog sync dependencies when no remote sync caller used them.
 */
internal fun closeInitializedAbsGraphResources(closeableResources: List<Lazy<*>>) {
    closeableResources.forEach { resource ->
        // Initialized Resource Guard (Prevents shutdown-only ABS dependency construction)
        // ABS coordinators own background scopes only after a caller first resolves them, so untouched resources are skipped.
        if (resource.isInitialized()) {
            runCatching { (resource.value as? Closeable)?.close() }
        }
    }
}

/**
 * UI Event Graph Resource Teardown (Closes initialized bridges before cancelling the owning scope)
 * Gives app-shell event translators an explicit close callback while keeping the scope cancellation as a final leak barrier.
 */
internal fun closeInitializedUiEventGraphResources(
    closeableResources: List<Lazy<*>>,
    eventBridgeScope: CoroutineScope
) {
    closeableResources.forEach { resource ->
        // Initialized Bridge Guard (Avoids allocating event translators during teardown)
        // Event bridges only own collector jobs after startup, so untouched bridges remain unconstructed.
        if (resource.isInitialized()) {
            runCatching { (resource.value as? Closeable)?.close() }
        }
    }
    // Event Scope Cancellation (Final coroutine leak barrier for app-level event collection)
    // Cancels any remaining child work even if an individual bridge close callback failed.
    eventBridgeScope.coroutineContext.get(Job)?.cancel()
}
