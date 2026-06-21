package com.viel.aplayer.di.graph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.Closeable

/**
 * Centralizes root di shutdown policy.
 * Stops playback runtime publishers before closing remote sync, local library, and UI event di resources.
 */
internal fun closeAppGraphsInLifecycleOrder(
    media: Closeable,
    download: Closeable,
    library: Closeable,
    abs: Closeable,
    uiEvents: Closeable
) {
    runCatching { media.close() }
    runCatching { download.close() }
    runCatching { abs.close() }
    runCatching { library.close() }
    runCatching { uiEvents.close() }
}

/**
 * Releases only initialized media-runtime resources.
 * Keeps container shutdown from constructing playback infrastructure while still giving active runtime resources a deterministic release hook.
 */
internal fun <T> releaseInitializedMediaGraphResource(
    resource: Lazy<T>,
    release: (T) -> Unit
) {
    if (resource.isInitialized()) {
        runCatching { release(resource.value) }
    }
}

/**
 * Closes only runtime-created library resources.
 * Avoids constructing lazy services during shutdown while still cancelling every initialized Closeable and recovery coroutine owner.
 */
internal fun closeInitializedLibraryGraphResources(
    closeableResources: List<Lazy<*>>,
    recoveryScope: CoroutineScope?
) {
    closeableResources.forEach { resource ->
        if (resource.isInitialized()) {
            runCatching { (resource.value as? Closeable)?.close() }
        }
    }
    recoveryScope?.coroutineContext?.get(Job)?.cancel()
}

/**
 * Closes initialized remote background coordinators.
 * Keeps ABS shutdown deterministic without allocating catalog sync dependencies when no remote sync caller used them.
 */
internal fun closeInitializedAbsGraphResources(closeableResources: List<Lazy<*>>) {
    closeableResources.forEach { resource ->
        if (resource.isInitialized()) {
            runCatching { (resource.value as? Closeable)?.close() }
        }
    }
}

/**
 * Closes initialized bridges before cancelling the owning scope.
 * Gives app-shell event translators an explicit close callback while keeping the scope cancellation as a final leak barrier.
 */
internal fun closeInitializedUiEventGraphResources(
    closeableResources: List<Lazy<*>>,
    eventBridgeScope: CoroutineScope
) {
    closeableResources.forEach { resource ->
        if (resource.isInitialized()) {
            runCatching { (resource.value as? Closeable)?.close() }
        }
    }
    eventBridgeScope.coroutineContext[Job]?.cancel()
}
