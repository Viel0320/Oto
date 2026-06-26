package com.viel.oto.library.orchestrator

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val DEFAULT_SCOPE_IO_CONCURRENCY: Int = 4

internal suspend fun <T, R> Iterable<T>.mapWithBoundedConcurrency(
    maxConcurrent: Int = DEFAULT_SCOPE_IO_CONCURRENCY,
    transform: suspend (T) -> R
): List<R> {
    val items = toList()
    if (items.isEmpty()) return emptyList()

    val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))
    return coroutineScope {
        items.map { item ->
            async {
                semaphore.withPermit {
                    transform(item)
                }
            }
        }.awaitAll()
    }
}
