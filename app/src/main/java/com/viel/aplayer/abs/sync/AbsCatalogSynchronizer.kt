package com.viel.aplayer.abs.sync

import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.mapping.AbsCatalogMapper
import com.viel.aplayer.abs.mapping.AbsRemoteIdMapper
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.data.cache.CoverCacheInvalidationPolicy
import com.viel.aplayer.data.cache.OnlineSourceCachePolicy
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.runCatchingCancellable
import com.viel.aplayer.logger.AbsSyncLogger
import com.viel.aplayer.logger.CacheDiagnosticsLogger
import java.util.UUID

class AbsCatalogSynchronizer(
    private val apiClient: AbsApiClient,
    private val credentialStore: AbsCredentialStore,
    private val catalogStore: AbsCatalogStore,
    private val coverCache: AbsCoverStore? = null,
    private val idMapper: AbsRemoteIdMapper = AbsRemoteIdMapper(),
    private val catalogMapper: AbsCatalogMapper = AbsCatalogMapper(idMapper),
    private val authorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer? = null,
    private val batchSize: Int = 20
) {
    /**
     * Sync Cancellation Boundary (Preserve WorkManager and coordinator cancellation semantics)
     * ABS catalog synchronization can recover ordinary remote or item failures, but coroutine cancellation must stop the whole sync immediately.
     */
    private inline fun <T> catalogRunCatching(block: () -> T): Result<T> =
        runCatchingCancellable(block)

    /**
     * Documented Sync Entrypoint (Executes synchronization and constructs a descriptive summary for setting toast prompts)
     * Does not alter synchronization behavior, solely extending output to record detailed book statistics.
     */
    suspend fun syncRootWithSummary(root: LibraryRootEntity): AbsSyncSummary {
        val start = AbsSyncLogger.mark()
        AbsSyncLogger.logSyncRootStart(rootId = root.id, libraryId = root.basePath)
        return catalogRunCatching {
            syncRootInternal(root)
        }.onFailure { error ->
            val redacted = error.message?.replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
                ?: error::class.java.simpleName
            catalogStore.saveSyncState(
                (catalogStore.getSyncState(root.id) ?: AbsSyncStateEntity(
                    rootId = root.id,
                    serverKey = "",
                    libraryId = root.basePath
                )).copy(
                    rootId = root.id,
                    libraryId = root.basePath,
                    lastError = redacted
                )
            )
            AbsSyncLogger.logSyncRootFailure(
                rootId = root.id,
                costMs = AbsSyncLogger.elapsedMs(start),
                errorClass = error::class.java.simpleName,
                message = redacted
            )
        }.getOrThrow()
    }

    suspend fun inspectRootSyncPlan(root: LibraryRootEntity): AbsSyncPlan {
        require(root.sourceType == AudiobookSchema.LibrarySourceType.ABS) { "Only ABS roots are supported" }
        val start = AbsSyncLogger.mark()
        // Logging Plan Inspection (Differentiates scheduler execution locks from active verification boundaries)
        AbsSyncLogger.logInspectPlanStart(rootId = root.id, libraryId = root.basePath)
        val credential = requireNotNull(credentialStore.get(root.credentialId)) {
            "Missing ABS credential for root ${root.id}"
        }
        val minified = apiClient.getLibraryItemsMinified(credential.baseUrl, credential.token, root.basePath)
        val total = minified.total ?: minified.results.orEmpty().size
        val plan = when {
            total <= 3000 -> AbsSyncPlan(totalItems = total, batchSize = 20, requiresConfirmation = false)
            total <= 10000 -> AbsSyncPlan(totalItems = total, batchSize = 20, requiresConfirmation = false)
            else -> AbsSyncPlan(totalItems = total, batchSize = 20, requiresConfirmation = true)
        }
        AbsSyncLogger.logInspectPlanSuccess(
            rootId = root.id,
            totalItems = plan.totalItems,
            batchSize = plan.batchSize,
            requiresConfirmation = plan.requiresConfirmation,
            costMs = AbsSyncLogger.elapsedMs(start)
        )
        return plan
    }

    suspend fun syncRoot(root: LibraryRootEntity) {
        // Legacy Sync Adapter (Bridges the legacy void calls to the new summary-returning execution channel)
        syncRootWithSummary(root)
        return
    }

    /**
     * Authorized Progress Refresh Due Check (Applies a root-scoped startup freshness gate)
     * The catalog sync state is the durable root-level remote refresh marker, so cold start can skip WorkManager enqueueing while the last successful ABS sync is still fresh.
     */
    suspend fun isAuthorizedProgressRefreshDue(rootId: String, nowMillis: Long): Boolean {
        val syncState = catalogStore.getSyncState(rootId)
        return isAbsAuthorizedProgressRefreshDue(syncState = syncState, nowMillis = nowMillis)
    }

    private suspend fun syncRootInternal(root: LibraryRootEntity): AbsSyncSummary {
        require(root.sourceType == AudiobookSchema.LibrarySourceType.ABS) { "Only ABS roots are supported" }
        // Internal Timing Marker (Establishes a localized stopwatch baseline to secure precise latency recordings in logs)
        val internalStart = AbsSyncLogger.mark()
        val credential = requireNotNull(credentialStore.get(root.credentialId)) {
            "Missing ABS credential for root ${root.id}"
        }
        val authorization = apiClient.authorize(credential.baseUrl, credential.token)
        val userId = authorization.user?.id
        val serverKey = idMapper.serverKey(credential.baseUrl, userId)
        val now = System.currentTimeMillis()
        val syncRunId = UUID.randomUUID().toString()
        val status = apiClient.status(credential.baseUrl)
        val minified = apiClient.getLibraryItemsMinified(credential.baseUrl, credential.token, root.basePath)
        val existingMirrors = catalogStore.getMirrorsByRootId(root.id).associateBy { mirror -> mirror.remoteItemId }
        val existingSync = catalogStore.getSyncState(root.id)
        val minifiedItems = minified.results.orEmpty()
        val currentFingerprint = minifiedFingerprint(minifiedItems)
        // Catalog Detail Candidate Scope (Selects detail fetches only from catalog freshness rules)
        // Authorized progress no longer expands this queue because progress merging runs after structural catalog rows exist.
        val detailCandidateIds = selectAbsDetailCandidateIds(
            minifiedItems = minifiedItems,
            existingMirrors = existingMirrors,
            previousFullListFingerprint = existingSync?.fullListFingerprint,
            currentFullListFingerprint = currentFingerprint,
            nowMillis = now
        )
        AbsSyncLogger.logIncrementalSelection(
            rootId = root.id,
            totalItems = minifiedItems.size,
            detailCandidates = detailCandidateIds.size,
            reusedItems = (minifiedItems.size - detailCandidateIds.size).coerceAtLeast(0),
            fingerprintUnchanged = existingSync?.fullListFingerprint == currentFingerprint
        )
        // ABS Mirror Cache Diagnostics (Records reused mirror counts without exposing remote item identifiers)
        // The unified cache log needs only aggregate reuse size and a hashed root source to correlate incremental sync behavior safely.
        CacheDiagnosticsLogger.logCacheEvent(
            cacheType = "abs_mirror",
            operation = "selectDetailCandidates",
            hit = null,
            costMs = null,
            sourceHash = CacheDiagnosticsLogger.hashIdentifier(root.id),
            sizeBytes = (minifiedItems.size - detailCandidateIds.size).coerceAtLeast(0).toLong(),
            detail = "total=${minifiedItems.size}, detailCandidates=${detailCandidateIds.size}"
        )
        var hadBatchFailure = false
        val unresolvedDetailFailures = linkedMapOf<String, String>()
        // Summary Increment Logic (Tracks newly resolved items only, preventing already stored records from skewing counts)
        var addedBookCount = 0
        var syncedBookCount = 0
        var failedBookCount = 0
        val detailItems = detailCandidateIds.chunked(batchSize).flatMapIndexed { batchIndex, ids ->
            val batchStart = AbsSyncLogger.mark()
            // Logging Batch Transports (Logs batch-level parameters to identify transport issues during batch queries)
            AbsSyncLogger.logBatchRequest(rootId = root.id, batchIndex = batchIndex, batchSize = ids.size, itemIds = ids)
            catalogRunCatching {
                apiClient.batchGetItems(credential.baseUrl, credential.token, ids)
            }.fold(
                onSuccess = { items ->
                AbsSyncLogger.logBatchSuccess(
                    rootId = root.id,
                    batchIndex = batchIndex,
                    requested = ids.size,
                    returned = items.size,
                    costMs = AbsSyncLogger.elapsedMs(batchStart)
                )
                val returnedIds = items.mapNotNull { item -> item.id }.toSet()
                val missingIds = ids.filterNot { candidateId -> candidateId in returnedIds }
                if (missingIds.isEmpty()) {
                    items
                } else {
                    // Partial Batch Recovery Merge (Returns individual retry details to the sync pipeline)
                    // Result.onSuccess cannot transform the batch payload, so this fold branch explicitly appends recovered
                    // item details to prevent partial batch gaps from being treated as reusable stale mirrors.
                    val retryResult = fetchItemsIndividuallyWithRetry(
                        root = root,
                        baseUrl = credential.baseUrl,
                        token = credential.token,
                        itemIds = missingIds
                    )
                    hadBatchFailure = hadBatchFailure || retryResult.failures.isNotEmpty()
                    unresolvedDetailFailures.putAll(retryResult.failures)
                    items + retryResult.items
                }
                },
                onFailure = { error ->
                AbsSyncLogger.logBatchFailure(
                    rootId = root.id,
                    batchIndex = batchIndex,
                    requested = ids.size,
                    costMs = AbsSyncLogger.elapsedMs(batchStart),
                    errorClass = error::class.java.simpleName,
                    message = error.message
                )
                val retryResult = fetchItemsIndividuallyWithRetry(
                    root = root,
                    baseUrl = credential.baseUrl,
                    token = credential.token,
                    itemIds = ids
                )
                hadBatchFailure = hadBatchFailure || retryResult.failures.isNotEmpty()
                unresolvedDetailFailures.putAll(retryResult.failures)
                retryResult.items
                }
            )
        }
        // Catalog Detail Mapping (Keeps remote progress out of book/file/chapter materialization)
        // Authorized progress is merged after catalog rows exist, through AbsAuthorizedProgressSynchronizer.
        val detailById = detailItems.associateBy { item -> item.id }
        val reusedMirrors = mutableListOf<AbsItemMirrorEntity>()
        for (minifiedItem in minifiedItems) {
            val remoteItemId = minifiedItem.id ?: continue
            val detail = detailById[remoteItemId]
            if (detail != null) {
                if (!isAbsPlayableBook(detail)) {
                    AbsSyncLogger.logSkipUnplayableItem(rootId = root.id, itemId = detail.id, mediaType = detail.mediaType)
                    continue
                }
                catalogRunCatching {
                    upsertItem(
                        root = root,
                        serverKey = serverKey,
                        item = detail,
                        now = now,
                        syncRunId = syncRunId,
                        existingMirror = existingMirrors[remoteItemId],
                        existingSync = existingSync,
                        serverVersion = status.serverVersion,
                        fullListFingerprint = currentFingerprint
                    )
                }.fold(
                    onSuccess = {
                        syncedBookCount += 1
                        if (existingMirrors[remoteItemId] == null) {
                            addedBookCount += 1
                        }
                    },
                    onFailure = { error ->
                        // ABS Item Failure Logging (Routes item-level failures through the shared sanitizer)
                        // The sync loop preserves best-effort catalog materialization while Logcat output receives the final ABS redaction pass.
                        AbsSyncLogger.logItemMaterializationFailure(
                            rootId = root.id,
                            itemId = detail.id,
                            errorClass = error::class.java.simpleName,
                            message = error.message
                        )
                        failedBookCount += 1
                    }
                )
                continue
            }
            val existingMirror = existingMirrors[remoteItemId] ?: continue
            // Resilient Mirror Reuse (Preserves active records and marks mirrors ACTIVE to bypass redundant queries or premature STALE states)
            reusedMirrors += existingMirror.copy(
                lastSeenSyncRunId = syncRunId,
                lastSeenAt = now,
                remoteUpdatedAt = minifiedItem.updatedAt ?: existingMirror.remoteUpdatedAt,
                state = AudiobookSchema.AbsMirrorState.ACTIVE
            )
            if (existingMirror.state != AudiobookSchema.AbsMirrorState.ACTIVE) {
                catalogStore.updateBookStatus(existingMirror.localBookId, AudiobookSchema.BookStatus.READY)
            }
        }
        if (reusedMirrors.isNotEmpty()) {
            catalogStore.replaceMirrors(reusedMirrors)
        }
        if (!hadBatchFailure) {
            markStaleCandidates(
                root = root,
                serverKey = serverKey,
                existingMirrors = existingMirrors,
                seenRemoteIds = minifiedItems.mapNotNull { item -> item.id }.toSet(),
                syncRunId = syncRunId,
                now = now,
                existingSync = existingSync,
                serverVersion = status.serverVersion,
                fullListFingerprint = currentFingerprint
            )
        } else {
            catalogStore.saveSyncState(
                (existingSync ?: AbsSyncStateEntity(
                    rootId = root.id,
                    serverKey = serverKey,
                    libraryId = root.basePath
                )).copy(
                    rootId = root.id,
                    serverKey = serverKey,
                    libraryId = root.basePath,
                    lastFullSyncAt = now,
                    lastIncrementalSyncAt = now,
                    serverVersion = status.serverVersion,
                    fullListFingerprint = currentFingerprint,
                    lastError = buildAbsIncrementalErrorSummary(unresolvedDetailFailures) ?: "DETAIL_BATCH_FAILED"
                )
            )
        }
        catalogStore.saveSyncState(
            (catalogStore.getSyncState(root.id) ?: AbsSyncStateEntity(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = root.basePath
            )).copy(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = root.basePath,
                lastFullSyncAt = now,
                lastIncrementalSyncAt = now,
                serverVersion = status.serverVersion,
                fullListFingerprint = currentFingerprint,
                lastError = buildAbsIncrementalErrorSummary(unresolvedDetailFailures)
            )
        )
        // Authorized Progress Final Merge (Routes the full user progress snapshot through the shared merger after catalog materialization)
        // Catalog sync now materializes only books, files, chapters, mirrors, and sync state before this dedicated progress pass runs.
        val authorizedProgressSummary = catalogRunCatching {
            authorizedProgressSynchronizer?.mergeAuthorizedProgress(
                root = root,
                baseUrl = credential.baseUrl,
                userId = userId,
                mediaProgress = authorization.user?.mediaProgress.orEmpty()
            ) ?: AbsAuthorizedProgressSyncSummary()
        }.getOrElse { error ->
            // Authorized Progress Best Effort (Keeps catalog materialization successful when progress merging fails)
            // The summary records this root as failed so diagnostics retain the progress problem without rolling back the completed catalog sync.
            AbsSyncLogger.logAuthorizedProgressSyncFailure(error::class.java.simpleName, error.message)
            AbsAuthorizedProgressSyncSummary(failedRootCount = 1)
        }
        AbsSyncLogger.logAuthorizedProgressRootMerge(
            rootId = root.id,
            remoteProgressCount = authorizedProgressSummary.remoteProgressCount,
            appliedCount = authorizedProgressSummary.appliedCount,
            skippedByResolverCount = authorizedProgressSummary.skippedByResolverCount,
            skippedMissingBookCount = authorizedProgressSummary.skippedMissingBookCount,
            failedRootCount = authorizedProgressSummary.failedRootCount
        )
        AbsSyncLogger.logSyncRootSuccess(
            rootId = root.id,
            minifiedCount = minifiedItems.size,
            detailCount = detailItems.size,
            hadBatchFailure = hadBatchFailure,
            costMs = AbsSyncLogger.elapsedMs(internalStart)
        )
        return AbsSyncSummary(
            totalItems = minifiedItems.size,
            addedBooks = addedBookCount,
            syncedBooks = syncedBookCount,
            failedItems = unresolvedDetailFailures.size + failedBookCount,
            reusedBooks = reusedMirrors.size,
            authorizedProgress = authorizedProgressSummary
        )
    }

    private suspend fun upsertItem(
        root: LibraryRootEntity,
        serverKey: String,
        item: AbsLibraryItemDto,
        now: Long,
        syncRunId: String,
        existingMirror: AbsItemMirrorEntity?,
        existingSync: AbsSyncStateEntity?,
        serverVersion: String?,
        fullListFingerprint: String
    ) {
        val existingBookEntity = catalogStore.getBookById(idMapper.bookId(serverKey, requireNotNull(item.id)))
        // Remote Version Invalidation (Propagates catalog-level cover changes even when cached file paths are stable)
        // ABS may update cover bytes behind the same local cache paths, so matching updatedAt against the existing mirror supplies a safe UI-key refresh signal.
        val remoteVersionChanged = item.updatedAt != null &&
            existingMirror?.remoteUpdatedAt != null &&
            item.updatedAt != existingMirror.remoteUpdatedAt
        val shouldRefreshCover = remoteVersionChanged || shouldRefreshAbsCover(existingBookEntity, now)
        val cachedCover = if (shouldRefreshCover) {
            runCatchingCancellable {
                coverCache?.downloadCover(root, requireNotNull(item.id))
            }.getOrNull()
        } else {
            null
        }
        // Cover Expiration Strategy (Aligns syncedAt parameters with true layout changes to secure UI image caches)
        // Returns fresh syncedAt values only for new books or if paths shift; otherwise returns cached stamps.
        val resolvedCoverPath = cachedCover?.originalPath ?: existingBookEntity?.coverPath
        val resolvedThumbnailPath = cachedCover?.thumbnailPath ?: existingBookEntity?.thumbnailPath
        val resolvedLastScannedAt = CoverCacheInvalidationPolicy.resolveLastScannedAt(
            existing = existingBookEntity,
            nextCoverPath = resolvedCoverPath,
            nextThumbnailPath = resolvedThumbnailPath,
            syncedAt = now,
            remoteVersionChanged = remoteVersionChanged || cachedCover != null
        )
        // Catalog-Only Book Mapping (Builds metadata without applying remote progress or read-status overrides)
        // Playback state reconciliation is centralized in AbsAuthorizedProgressSynchronizer to keep catalog writes deterministic.
        val book = catalogMapper.toBook(
            root = root,
            serverKey = serverKey,
            item = item,
            existing = existingBookEntity,
            syncedAt = now,
            lastScannedAt = resolvedLastScannedAt,
            coverPath = resolvedCoverPath,
            thumbnailPath = resolvedThumbnailPath,
            // Deprecated: backgroundColorArgb is no longer passed during book upsert
        )
        val files = catalogMapper.toFiles(root, serverKey, item)
        val chapters = catalogMapper.toChapters(serverKey, item, files)
        val mirror = AbsItemMirrorEntity(
            localBookId = book.id,
            rootId = root.id,
            serverKey = serverKey,
            remoteItemId = requireNotNull(item.id),
            lastSeenSyncRunId = syncRunId,
            lastSeenAt = now,
            remoteUpdatedAt = item.updatedAt,
            state = AudiobookSchema.AbsMirrorState.ACTIVE
        )
        val syncState = (existingSync ?: AbsSyncStateEntity(
            rootId = root.id,
            serverKey = serverKey,
            libraryId = root.basePath
        )).copy(
            serverKey = serverKey,
            libraryId = root.basePath,
            lastFullSyncAt = now,
            serverVersion = serverVersion,
            fullListFingerprint = fullListFingerprint,
            lastError = null
        )
        catalogStore.upsertCatalogMirror(
            book = book,
            files = files,
            chapters = chapters,
            mirror = mirror,
            syncState = syncState
        )
        // Catalog Item Materialization Log (Reports only catalog-shaped rows written by this synchronizer)
        // Remote progress is excluded from this log because it is handled by the authorized progress synchronizer.
        AbsSyncLogger.logUpsertItem(
            rootId = root.id,
            itemId = item.id,
            bookId = book.id,
            fileCount = files.size,
            chapterCount = chapters.size
        )
    }

    private suspend fun markStaleCandidates(
        root: LibraryRootEntity,
        serverKey: String,
        existingMirrors: Map<String, AbsItemMirrorEntity>,
        seenRemoteIds: Set<String>,
        syncRunId: String,
        now: Long,
        existingSync: AbsSyncStateEntity?,
        serverVersion: String?,
        fullListFingerprint: String
    ) {
        val staleMirrors = existingMirrors.values
            .filter { mirror -> mirror.remoteItemId !in seenRemoteIds && mirror.state == AudiobookSchema.AbsMirrorState.ACTIVE }
            .map { mirror ->
                mirror.copy(
                    lastSeenSyncRunId = syncRunId,
                    lastSeenAt = now,
                    state = AudiobookSchema.AbsMirrorState.STALE
                )
            }
        val remoteDeletedMirrors = existingMirrors.values
            .filter { mirror -> mirror.remoteItemId !in seenRemoteIds && mirror.state == AudiobookSchema.AbsMirrorState.STALE }
            .map { mirror ->
                mirror.copy(
                    lastSeenSyncRunId = syncRunId,
                    lastSeenAt = now,
                    state = AudiobookSchema.AbsMirrorState.REMOTE_DELETED
                )
            }
        if (staleMirrors.isNotEmpty()) {
            catalogStore.replaceMirrors(staleMirrors)
            AbsSyncLogger.logMarkStale(rootId = root.id, count = staleMirrors.size)
        }
        remoteDeletedMirrors.forEach { mirror ->
            catalogStore.updateBookStatus(mirror.localBookId, AudiobookSchema.BookStatus.DELETED)
        }
        if (remoteDeletedMirrors.isNotEmpty()) {
            catalogStore.replaceMirrors(remoteDeletedMirrors)
            // Logging Evictions (Explicitly logs remote deletions to track potential sync loss and verify cascade removals)
            AbsSyncLogger.logMarkRemoteDeleted(rootId = root.id, count = remoteDeletedMirrors.size)
        }
        catalogStore.saveSyncState(
            (existingSync ?: AbsSyncStateEntity(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = root.basePath
            )).copy(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = root.basePath,
                lastFullSyncAt = now,
                serverVersion = serverVersion,
                fullListFingerprint = fullListFingerprint,
                lastError = null
            )
        )
    }

    /**
     * Single-Item Fallback Retries (Invokes isolated single-item lookups on failure up to three times)
     * Records failure signatures under sync state bounds instead of propagating exceptions.
     */
    private suspend fun fetchItemsIndividuallyWithRetry(
        root: LibraryRootEntity,
        baseUrl: String,
        token: String,
        itemIds: List<String>
    ): DetailRetryResult {
        val resolvedItems = mutableListOf<AbsLibraryItemDto>()
        val failures = linkedMapOf<String, String>()
        itemIds.forEach { itemId ->
            var resolved: AbsLibraryItemDto? = null
            var lastFailureReason: String? = null
            for (attempt in 1..MAX_DETAIL_RETRY_ATTEMPTS) {
                AbsSyncLogger.logItemRetryStart(rootId = root.id, itemId = itemId, attempt = attempt, maxAttempts = MAX_DETAIL_RETRY_ATTEMPTS)
                val result = catalogRunCatching {
                    apiClient.batchGetItems(baseUrl, token, listOf(itemId))
                }
                result.onSuccess { items ->
                    resolved = items.firstOrNull { item -> item.id == itemId }
                    if (resolved != null) {
                        AbsSyncLogger.logItemRetrySuccess(rootId = root.id, itemId = itemId, attempt = attempt)
                    } else {
                        lastFailureReason = "DETAIL_MISSING_FROM_BATCH"
                        AbsSyncLogger.logItemRetryFailure(
                            rootId = root.id,
                            itemId = itemId,
                            attempt = attempt,
                            maxAttempts = MAX_DETAIL_RETRY_ATTEMPTS,
                            errorClass = "IllegalStateException",
                            message = lastFailureReason
                        )
                    }
                }.onFailure { error ->
                    lastFailureReason = error.message ?: error::class.java.simpleName
                    AbsSyncLogger.logItemRetryFailure(
                        rootId = root.id,
                        itemId = itemId,
                        attempt = attempt,
                        maxAttempts = MAX_DETAIL_RETRY_ATTEMPTS,
                        errorClass = error::class.java.simpleName,
                        message = error.message
                    )
                }
                if (resolved != null) {
                    break
                }
            }
            if (resolved != null) {
                resolvedItems += resolved
            } else {
                val finalReason = lastFailureReason ?: "DETAIL_RETRY_FAILED"
                AbsSyncLogger.logItemRetryGiveUp(rootId = root.id, itemId = itemId, maxAttempts = MAX_DETAIL_RETRY_ATTEMPTS, reason = finalReason)
                failures[itemId] = finalReason
            }
        }
        return DetailRetryResult(items = resolvedItems, failures = failures)
    }

    /**
     * Compute Minified Fingerprint Hash (Optimizes fingerprint storage size and comparison performance)
     * Hashes the minified items list with SHA-256 to produce a compact, fixed-size fingerprint.
     */
    private fun minifiedFingerprint(items: List<AbsLibraryItemDto>): String =
        Companion.minifiedFingerprint(items)

    private fun shouldRefreshAbsCover(existingBookEntity: BookEntity?, now: Long): Boolean {
        // ABS Cover TTL Fallback (Refreshes remote artwork periodically even when catalog paths remain stable)
        // New books and version-changed items still refresh through normal materialization; this guard prevents old local cover files from being trusted beyond the shared online policy window.
        if (existingBookEntity?.coverPath == null && existingBookEntity?.thumbnailPath == null) return true
        return !OnlineSourceCachePolicy.isFresh(
            cachedAtMillis = existingBookEntity.lastScannedAt,
            nowMillis = now,
            ttlMillis = OnlineSourceCachePolicy.ABS_COVER_TTL_MS
        )
    }

    private data class DetailRetryResult(
        val items: List<AbsLibraryItemDto>,
        val failures: Map<String, String>
    )

    companion object {
        private const val MAX_DETAIL_RETRY_ATTEMPTS = 3

        /**
         * Compute Minified Fingerprint Hash (Shared calculation function for remote list delta tracking)
         * Converts the minified catalog identifiers into a 64-character SHA-256 representation.
         */
        fun minifiedFingerprint(items: List<AbsLibraryItemDto>): String {
            val raw = items.joinToString(separator = "|") { item -> "${item.id}:${item.updatedAt}" }
            val bytes = raw.toByteArray(Charsets.UTF_8)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Playability Inspection (Verifies item format criteria to ensure that the parsed entity possesses valid media tracks)
 * Requires mediaType as "book" and non-empty track records; items without media URLs are skipped.
 */
internal fun isAbsPlayableBook(item: AbsLibraryItemDto): Boolean =
    item.mediaType.equals("book", ignoreCase = true) &&
        (item.media?.tracks?.isNotEmpty() == true)

/**
 * Authorized Progress Freshness Policy (Evaluates startup progress refresh age without constructing sync adapters)
 * Startup warmup and catalog synchronization share this pure rule so freshness reads can stay on DAO data until stale roots need WorkManager scheduling.
 */
internal fun isAbsAuthorizedProgressRefreshDue(syncState: AbsSyncStateEntity?, nowMillis: Long): Boolean =
    !OnlineSourceCachePolicy.isFresh(
        cachedAtMillis = syncState?.lastIncrementalSyncAt ?: syncState?.lastFullSyncAt,
        nowMillis = nowMillis,
        ttlMillis = OnlineSourceCachePolicy.ABS_AUTHORIZED_PROGRESS_TTL_MS
    )

/**
 * Sync Candidate Filter (Analyzes minified lists and timestamps to identify entries requiring deep details)
 * Excludes static segments while routing updated elements to optimize payload sizes and database transactions.
 */
internal fun selectAbsDetailCandidateIds(
    minifiedItems: List<AbsLibraryItemDto>,
    existingMirrors: Map<String, AbsItemMirrorEntity>,
    previousFullListFingerprint: String?,
    currentFullListFingerprint: String,
    nowMillis: Long = System.currentTimeMillis()
): List<String> {
    val fingerprintUnchanged = previousFullListFingerprint != null && previousFullListFingerprint == currentFullListFingerprint
    return minifiedItems.mapNotNull { item ->
        val remoteItemId = item.id ?: return@mapNotNull null
        val existingMirror = existingMirrors[remoteItemId]
        if (shouldFetchAbsItemDetail(item, existingMirror, fingerprintUnchanged, nowMillis)) {
            remoteItemId
        } else {
            null
        }
    }
}

/**
 * Dynamic Fetch Rules (Assesses mirror structures and server updates to decide if metadata should reload)
 * Rules:
 * 1. Missing local mirrors demand fresh metadata queries.
 * 2. If fingerprints are unchanged, existing mirrors are preserved directly.
 * 3. If signatures differ, compares remote and local timestamps, querying on mismatch.
 */
internal fun shouldFetchAbsItemDetail(
    item: AbsLibraryItemDto,
    existingMirror: AbsItemMirrorEntity?,
    fingerprintUnchanged: Boolean,
    nowMillis: Long = System.currentTimeMillis()
): Boolean {
    if (item.id == null) return false
    if (existingMirror == null) return true
    // ABS Catalog Mirror TTL Fallback (Forces periodic detail refreshes even when the minified list fingerprint is unchanged)
    // The remote updatedAt and fingerprint checks remain primary, while this bound protects against server-side timestamp omissions or missed minified changes.
    if (!OnlineSourceCachePolicy.isFresh(
            cachedAtMillis = existingMirror.lastSeenAt,
            nowMillis = nowMillis,
            ttlMillis = OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS
        )
    ) {
        return true
    }
    if (fingerprintUnchanged) return false
    val remoteUpdatedAt = item.updatedAt
    val localUpdatedAt = existingMirror.remoteUpdatedAt
    return when {
        remoteUpdatedAt == null -> true
        localUpdatedAt == null -> true
        else -> remoteUpdatedAt != localUpdatedAt
    }
}

/**
 * Aggregate Item Failures (Error summary builder)
 * Compiles individual item failures into a compact root-level summary for log diagnostics and UI settings indicators.
 * Intentionally isolates remote payload details, showing only failure count and the first item's root cause to avoid string bloating.
 */
internal fun buildAbsIncrementalErrorSummary(failures: Map<String, String>): String? {
    if (failures.isEmpty()) return null
    val first = failures.entries.first()
    return "DETAIL_ITEM_FAILED:${failures.size}:first=${first.key}:${first.value}"
}

/**
 * Synchronization Ingestion Summary (Result statistics wrapper)
 * Returns a lightweight summary of ingestion results to the settings view upon rescan completion.
 * Separates new books from refreshed/reused books, enabling accurate toast reporting of successes and failures.
 */
data class AbsSyncSummary(
    val totalItems: Int,
    val addedBooks: Int,
    val syncedBooks: Int,
    val failedItems: Int,
    val reusedBooks: Int,
    /**
     * Authorized Progress Summary (Reports the follow-up user progress merge for this catalog root)
     * Defaulting to an empty aggregate keeps legacy tests and callers focused on catalog counts unless they inspect progress sync explicitly.
     */
    val authorizedProgress: AbsAuthorizedProgressSyncSummary = AbsAuthorizedProgressSyncSummary()
)

data class AbsSyncPlan(
    val totalItems: Int,
    val batchSize: Int,
    val requiresConfirmation: Boolean
)
