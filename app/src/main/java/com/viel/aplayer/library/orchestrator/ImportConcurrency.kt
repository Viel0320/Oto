package com.viel.aplayer.library.orchestrator

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Default Scope Concurrency Limit (Memory and thermal guard)
// Limit concurrent I/O operations to 4 in a scope to prevent excessive memory and thermal pressure from concurrent tag or image parsing.
internal const val DEFAULT_SCOPE_IO_CONCURRENCY: Int = 4

// Ordered Concurrency Mapping (Stable output sequence)
// Maps items concurrently (e.g., metadata and covers) while preserving original input order for stable grouping and claim steps.
internal suspend fun <T, R> Iterable<T>.mapWithBoundedConcurrency(
    maxConcurrent: Int = DEFAULT_SCOPE_IO_CONCURRENCY,
    transform: suspend (T) -> R
): List<R> {
    val items = toList()
    if (items.isEmpty()) return emptyList()

    // Safe Semaphore Initialization (Fallback boundary check)
    // Ensures concurrency factor is at least 1, falling back to sequential execution if a zero or negative limit is supplied.
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
