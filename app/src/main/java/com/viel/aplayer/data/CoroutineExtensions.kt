package com.viel.aplayer.data

import kotlinx.coroutines.CancellationException

/**
 * Provides a safe wrapper that intercepts all Throwables except CancellationException to preserve coroutines structure.
 * Allows CancellationException to propagate naturally for structured concurrency.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
