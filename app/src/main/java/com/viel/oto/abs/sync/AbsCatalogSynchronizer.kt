package com.viel.oto.abs.sync

import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.mapping.AbsCatalogMapper
import com.viel.oto.abs.mapping.AbsRemoteIdMapper
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.data.abs.sync.AbsCatalogStore
import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.data.abs.sync.AbsSyncStateEntity
import com.viel.oto.data.cache.OnlineSourceCachePolicy
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.runCatchingCancellable
import com.viel.oto.logger.AbsLogSanitizer
import com.viel.oto.logger.AbsSyncLogger
import com.viel.oto.logger.CacheDiagnosticsLogger
import java.util.UUID

class AbsCatalogSynchronizer(
    private val apiClient: AbsApiClient,
    private val credentialStore: AbsCredentialStore,
    private val catalogStore: AbsCatalogStore,
    private val idMapper: AbsRemoteIdMapper = AbsRemoteIdMapper(),
    private val catalogMapper: AbsCatalogMapper = AbsCatalogMapper(idMapper),
    private val authorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer? = null,
    private val batchSize: Int = 20
) {
    /**
     * Preserve WorkManager and coordinator cancellation semantics.
     * ABS catalog synchronization can recover ordinary remote or item failures, but coroutine cancellation must stop the whole sync immediately.
     */
    private inline fun <T> catalogRunCatching(block: () -> T): Result<T> =
        runCatchingCancellable(block)

    /**
     * Executes synchronization and constructs a descriptive summary for setting toast prompts.
     * Does not alter synchronization behavior, solely extending output to record detailed book statistics.
     */
    suspend fun syncRootWithSummary(root: LibraryRootEntity): AbsSyncSummary {
        val start = AbsSyncLogger.mark()
        AbsSyncLogger.logSyncRootStart(rootId = root.id, libraryId = root.basePath)
        return catalogRunCatching {
            syncRootInternal(root)
        }.onFailure { error ->
            val redacted = error.message?.let { AbsLogSanitizer.sanitizeText(it) }
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
        AbsSyncLogger.logInspectPlanStart(rootId = root.id, libraryId = root.basePath)
        val credential = requireNotNull(credentialStore.get(root.credentialId)) {
            "Missing ABS credential for root ${root.id}"
        }
        val minified = apiClient.getLibraryItemsMinified(
            baseUrl = credential.baseUrl,
            token = credential.token,
            libraryId = root.basePath,
            credentialId = credential.id
        )
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
        syncRootWithSummary(root)
        return
    }

    /**
     * Applies a root-scoped startup freshness gate.
     * The catalog sync state is the durable root-level remote refresh marker, so cold start can skip WorkManager enqueueing while the last successful ABS sync is still fresh.
     */
    suspend fun isAuthorizedProgressRefreshDue(rootId: String, nowMillis: Long): Boolean {
        val syncState = catalogStore.getSyncState(rootId)
        return isAbsAuthorizedProgressRefreshDue(syncState = syncState, nowMillis = nowMillis)
    }

    private suspend fun syncRootInternal(root: LibraryRootEntity): AbsSyncSummary {
        require(root.sourceType == AudiobookSchema.LibrarySourceType.ABS) { "Only ABS roots are supported" }
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
        val minified = apiClient.getLibraryItemsMinified(
            baseUrl = credential.baseUrl,
            token = credential.token,
            libraryId = root.basePath,
            credentialId = credential.id
        )
        val existingMirrors = catalogStore.getMirrorsByRootId(root.id).associateBy { mirror -> mirror.remoteItemId }
        val existingSync = catalogStore.getSyncState(root.id)
        val minifiedItems = minified.results.orEmpty()
        val currentFingerprint = minifiedFingerprint(minifiedItems)
        /**
         * Resets fingerprint comparator on target library mismatches.
         * Detects if the library root basePath has shifted compared to the last sync state, treating the previous fingerprint as null if mismatched.
         */
        val isLibrarySwitched = existingSync != null && existingSync.libraryId != root.basePath
        val previousFingerprint = if (isLibrarySwitched) null else existingSync?.fullListFingerprint
        val detailCandidateIds = selectAbsDetailCandidateIds(
            minifiedItems = minifiedItems,
            existingMirrors = existingMirrors,
            previousFullListFingerprint = previousFingerprint,
            currentFullListFingerprint = currentFingerprint,
            nowMillis = now
        )
        AbsSyncLogger.logIncrementalSelection(
            rootId = root.id,
            totalItems = minifiedItems.size,
            detailCandidates = detailCandidateIds.size,
            reusedItems = (minifiedItems.size - detailCandidateIds.size).coerceAtLeast(0),
            fingerprintUnchanged = previousFingerprint == currentFingerprint
        )
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
        var addedBookCount = 0
        var syncedBookCount = 0
        var failedBookCount = 0
        val detailItems = detailCandidateIds.chunked(batchSize).flatMapIndexed { batchIndex, ids ->
            val batchStart = AbsSyncLogger.mark()
            AbsSyncLogger.logBatchRequest(rootId = root.id, batchIndex = batchIndex, batchSize = ids.size, itemIds = ids)
            catalogRunCatching {
                apiClient.batchGetItems(
                    baseUrl = credential.baseUrl,
                    token = credential.token,
                    itemIds = ids,
                    credentialId = credential.id
                )
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
                    val retryResult = fetchItemsIndividuallyWithRetry(
                        root = root,
                        baseUrl = credential.baseUrl,
                        token = credential.token,
                        credentialId = credential.id,
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
                    credentialId = credential.id,
                    itemIds = ids
                )
                hadBatchFailure = hadBatchFailure || retryResult.failures.isNotEmpty()
                unresolvedDetailFailures.putAll(retryResult.failures)
                retryResult.items
                }
            )
        }
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
        val authorizedProgressSummary = catalogRunCatching {
            authorizedProgressSynchronizer?.mergeAuthorizedProgress(
                root = root,
                baseUrl = credential.baseUrl,
                userId = userId,
                mediaProgress = authorization.user?.mediaProgress.orEmpty()
            ) ?: AbsAuthorizedProgressSyncSummary()
        }.getOrElse { error ->
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

    /**
     * Upserts an AudiobookShelf library item into the local database catalog.
     *
     * e.g. author, description, files. into local
     * database entities. Since cover synchronization is now decoupled from the catalog sync flow,
     * this function only handles metadata mapping. If the remote item version changes, it
     * invalidates the cover paths and scanned timestamp in the database to allow the self-healing
     * flow to lazily fetch the new cover.
     */
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
        val remoteVersionChanged = item.updatedAt != null &&
            existingMirror?.remoteUpdatedAt != null &&
            item.updatedAt != existingMirror.remoteUpdatedAt

        val resolvedCoverPath = if (remoteVersionChanged) null else existingBookEntity?.coverPath
        val resolvedThumbnailPath = if (remoteVersionChanged) null else existingBookEntity?.thumbnailPath
        val resolvedLastScannedAt = if (remoteVersionChanged) 0L else (existingBookEntity?.lastScannedAt ?: 0L)
        val book = catalogMapper.toBook(
            root = root,
            serverKey = serverKey,
            item = item,
            existing = existingBookEntity,
            syncedAt = now,
            lastScannedAt = resolvedLastScannedAt,
            coverPath = resolvedCoverPath,
            thumbnailPath = resolvedThumbnailPath,
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
     * Invokes isolated single-item lookups on failure up to three times.
     * Records failure signatures under sync state bounds instead of propagating exceptions.
     */
    private suspend fun fetchItemsIndividuallyWithRetry(
        root: LibraryRootEntity,
        baseUrl: String,
        token: String,
        credentialId: String,
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
                    apiClient.batchGetItems(
                        baseUrl = baseUrl,
                        token = token,
                        itemIds = listOf(itemId),
                        credentialId = credentialId
                    )
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
     * Optimizes fingerprint storage size and comparison performance.
     * Hashes the minified items list with SHA-256 to produce a compact, fixed-size fingerprint.
     */
    private fun minifiedFingerprint(items: List<AbsLibraryItemDto>): String =
        AbsCatalogSynchronizer.minifiedFingerprint(items)


    private data class DetailRetryResult(
        val items: List<AbsLibraryItemDto>,
        val failures: Map<String, String>
    )

    companion object {
        private const val MAX_DETAIL_RETRY_ATTEMPTS = 3

        /**
         * Shared calculation function for remote list delta tracking.
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
 * Verifies item format criteria to ensure that the parsed entity possesses valid media tracks.
 * Requires mediaType as "book" and non-empty track records; items without media URLs are skipped.
 */
internal fun isAbsPlayableBook(item: AbsLibraryItemDto): Boolean =
    item.mediaType.equals("book", ignoreCase = true) &&
        (item.media?.tracks?.isNotEmpty() == true)

/**
 * Evaluates startup progress refresh age without constructing sync adapters.
 * Startup warmup and catalog synchronization share this pure rule so freshness reads can stay on DAO data until stale roots need WorkManager scheduling.
 */
internal fun isAbsAuthorizedProgressRefreshDue(syncState: AbsSyncStateEntity?, nowMillis: Long): Boolean =
    !OnlineSourceCachePolicy.isFresh(
        cachedAtMillis = syncState?.lastIncrementalSyncAt ?: syncState?.lastFullSyncAt,
        nowMillis = nowMillis,
        ttlMillis = OnlineSourceCachePolicy.ABS_AUTHORIZED_PROGRESS_TTL_MS
    )

/**
 * Analyzes minified lists and timestamps to identify entries requiring deep details.
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
 * Assesses mirror structures and server updates to decide if metadata should reload.
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
 * Error summary builder.
 * Compiles individual item failures into a compact root-level summary for log diagnostics and UI settings indicators.
 * Intentionally isolates remote payload details, showing only failure count and the first item's root cause to avoid string bloating.
 */
internal fun buildAbsIncrementalErrorSummary(failures: Map<String, String>): String? {
    if (failures.isEmpty()) return null
    val first = failures.entries.first()
    return "DETAIL_ITEM_FAILED:${failures.size}:first=${first.key}:${first.value}"
}

/**
 * Result statistics wrapper.
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
     * Reports the follow-up user progress merge for this catalog root.
     * Defaulting to an empty aggregate keeps legacy tests and callers focused on catalog counts unless they inspect progress sync explicitly.
     */
    val authorizedProgress: AbsAuthorizedProgressSyncSummary = AbsAuthorizedProgressSyncSummary()
)

data class AbsSyncPlan(
    val totalItems: Int,
    val batchSize: Int,
    val requiresConfirmation: Boolean
)
