package com.viel.aplayer.data.cover

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.logger.ScanWorkflowLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cover Recovery Service (Implements CoverRecoveryGateway)
 *
 * Thin forwarder over the [CoverSelfHealer] seam (backed by [CoverRecoveryHelper], now colocated under data/cover).
 * Catalog reads, metadata refresh, and scan passes route their self-heal triggers here so they depend on this
 * contract rather than the concrete helper. The healer owns dedup, presence caching, and the regeneration scope,
 * so each call stays cheap and idempotent.
 */
class CoverRecoveryGatewayImpl(
    private val bookDao: BookDao,
    private val coverSelfHealer: CoverSelfHealer,
    private val sweepPolicy: CoverRecoverySweepPolicy = CoverRecoverySweepPolicy()
) : CoverRecoveryGateway {
    override fun triggerRecovery(book: BookEntity) =
        coverSelfHealer.checkAndTriggerCoverRegeneration(book)

    override suspend fun forceRegenerate(bookId: String): Boolean =
        coverSelfHealer.forceRegenerateCover(bookId)

    override suspend fun recoverMissingCovers() = withContext(Dispatchers.IO) {
        val candidateLimit = sweepPolicy.maxBooksPerSweep.coerceAtLeast(0)
        if (candidateLimit == 0) return@withContext
        val batchSize = sweepPolicy.batchSize.coerceAtLeast(1)
        val books = bookDao.getCoverRecoveryCandidates(candidateLimit)
        ScanWorkflowLogger.debug(
            "coverRecovery sweep start: candidates=${books.size}, limit=$candidateLimit, batchSize=$batchSize"
        )
        books.forEachIndexed { index, book ->
            if (index > 0 && index % batchSize == 0) {
                yield()
                if (sweepPolicy.batchDelayMs > 0L) {
                    delay(sweepPolicy.batchDelayMs.milliseconds)
                }
            }
            triggerRecovery(book)
        }
        ScanWorkflowLogger.debug("coverRecovery sweep complete: checked=${books.size}")
    }
}

/**
 * Cover Recovery Sweep Policy (Startup resource budget for artwork self-healing)
 * Keeps the Home-start recovery pass bounded and cooperative so SAF queries, media parsing, and cache checks do
 * not compete with first paint or immediate list scrolling.
 */
data class CoverRecoverySweepPolicy(
    val maxBooksPerSweep: Int = DEFAULT_MAX_BOOKS_PER_SWEEP,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val batchDelayMs: Long = DEFAULT_BATCH_DELAY_MS
) {
    private companion object {
        private const val DEFAULT_MAX_BOOKS_PER_SWEEP = 48
        private const val DEFAULT_BATCH_SIZE = 8
        private const val DEFAULT_BATCH_DELAY_MS = 120L
    }
}
