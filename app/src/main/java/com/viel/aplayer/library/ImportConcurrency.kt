package com.viel.aplayer.library

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// 详尽的中文注释：scope 内 I/O 并发默认限制为 4，避免大量元数据探测或图片解码任务同时启动导致内存和温度压力过高。
internal const val DEFAULT_SCOPE_IO_CONCURRENCY: Int = 4

// 详尽的中文注释：按输入顺序返回并发结果，让元数据、封面、时长读取可以并发执行，同时不改变后续启发式聚合和 claim 的稳定顺序。
internal suspend fun <T, R> Iterable<T>.mapWithBoundedConcurrency(
    maxConcurrent: Int = DEFAULT_SCOPE_IO_CONCURRENCY,
    transform: suspend (T) -> R
): List<R> {
    val items = toList()
    if (items.isEmpty()) return emptyList()

    // 详尽的中文注释：将异常并发度兜底为 1，保证调用方即使传入 0 或负数也会退化为稳定串行执行。
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
