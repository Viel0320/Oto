package com.viel.aplayer.data.cover

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val coverSelfHealer: CoverSelfHealer
) : CoverRecoveryGateway {
    override fun triggerRecovery(book: BookEntity) =
        coverSelfHealer.checkAndTriggerCoverRegeneration(book)

    override suspend fun forceRegenerate(bookId: String): Boolean =
        coverSelfHealer.forceRegenerateCover(bookId)

    override suspend fun recoverMissingCovers() = withContext(Dispatchers.IO) {
        bookDao.getAllBooksOnce().forEach { book -> triggerRecovery(book) }
    }
}
