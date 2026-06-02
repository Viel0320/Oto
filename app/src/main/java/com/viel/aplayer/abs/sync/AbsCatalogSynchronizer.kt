package com.viel.aplayer.abs.sync

import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.mapping.AbsCatalogMapper
import com.viel.aplayer.abs.mapping.AbsProgressMapper
import com.viel.aplayer.abs.mapping.AbsRemoteIdMapper
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.logger.AbsSyncLogger
import java.util.UUID

class AbsCatalogSynchronizer(
    private val apiClient: AbsApiClient,
    private val credentialStore: AbsCredentialStore,
    private val catalogStore: AbsCatalogStore,
    private val coverCache: AbsCoverStore? = null,
    private val idMapper: AbsRemoteIdMapper = AbsRemoteIdMapper(),
    private val progressMapper: AbsProgressMapper = AbsProgressMapper(),
    private val catalogMapper: AbsCatalogMapper = AbsCatalogMapper(idMapper, progressMapper),
    private val batchSize: Int = 20
) {
    /**
     * 详尽的中文注释：带摘要结果的同步入口，供设置页在后台自动同步完成后生成更有信息量的 toast。
     * 它不会改变现有同步语义，只是在原有执行结果之外，额外返回“新增成功多少本、失败多少本”的统计。
     */
    suspend fun syncRootWithSummary(root: LibraryRootEntity): AbsSyncSummary {
        val start = AbsSyncLogger.mark()
        AbsSyncLogger.logSyncRootStart(rootId = root.id, libraryId = root.basePath)
        return runCatching {
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
        // 详尽中文注释：同步计划预检查是大库确认弹窗的入口，必须单独记日志，便于区分“同步器没跑”和“被确认门槛挡住”。
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
        // 详尽的中文注释：兼容旧调用方的无返回值入口直接委托到带摘要的新入口，
        // 这样既保留原有行为，又能让新的设置页自动同步 toast 拿到统计结果。
        syncRootWithSummary(root)
        return
    }

    private suspend fun syncRootInternal(root: LibraryRootEntity): AbsSyncSummary {
        require(root.sourceType == AudiobookSchema.LibrarySourceType.ABS) { "Only ABS roots are supported" }
        // 详尽中文注释：内部同步流程再单独维护一个耗时起点，避免误用外层包装方法的计时变量，保证日志口径稳定。
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
        val detailCandidateIds = selectAbsDetailCandidateIds(
            minifiedItems = minifiedItems,
            existingMirrors = existingMirrors,
            previousFullListFingerprint = existingSync?.fullListFingerprint,
            currentFullListFingerprint = currentFingerprint
        )
        AbsSyncLogger.logIncrementalSelection(
            rootId = root.id,
            totalItems = minifiedItems.size,
            detailCandidates = detailCandidateIds.size,
            reusedItems = (minifiedItems.size - detailCandidateIds.size).coerceAtLeast(0),
            fingerprintUnchanged = existingSync?.fullListFingerprint == currentFingerprint
        )
        var hadBatchFailure = false
        val unresolvedDetailFailures = linkedMapOf<String, String>()
        // 详尽的中文注释：摘要统计只记录“本轮真正新增入库的书本数”和“本轮 detail 最终失败的条目数”，
        // 不把历史镜像复用算成新增，避免 toast 误导用户。
        var addedBookCount = 0
        var syncedBookCount = 0
        val detailItems = detailCandidateIds.chunked(batchSize).flatMapIndexed { batchIndex, ids ->
            val batchStart = AbsSyncLogger.mark()
            // 详尽中文注释：batch/get 是 ABS mirror 最容易出局部失败的地方，因此单独记录每批请求的 item 集合与结果。
            AbsSyncLogger.logBatchRequest(rootId = root.id, batchIndex = batchIndex, batchSize = ids.size, itemIds = ids)
            runCatching {
                apiClient.batchGetItems(credential.baseUrl, credential.token, ids)
            }.onSuccess { items ->
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
                        itemIds = missingIds
                    )
                    hadBatchFailure = hadBatchFailure || retryResult.failures.isNotEmpty()
                    unresolvedDetailFailures.putAll(retryResult.failures)
                    items + retryResult.items
                }
            }.getOrElse { error ->
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
                upsertItem(
                    root = root,
                    serverKey = serverKey,
                    item = detail,
                    now = now,
                    syncRunId = syncRunId,
                    existingSync = existingSync,
                    serverVersion = status.serverVersion,
                    fullListFingerprint = currentFingerprint
                )
                syncedBookCount += 1
                if (existingMirrors[remoteItemId] == null) {
                    // 详尽的中文注释：只有本地此前不存在 mirror 的条目，才算“成功新增添加的书本数”。
                    addedBookCount += 1
                }
                continue
            }
            val existingMirror = existingMirrors[remoteItemId] ?: continue
            // 详尽的中文注释：未进入详情队列或详情最终失败，但本地已有历史镜像时，
            // 直接复用历史 book/files/chapters，并把 mirror 的 seen 状态刷新为 ACTIVE，
            // 避免无变化条目每轮都重拉详情，也避免单 item 失败把既有书错误打成 STALE。
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
            failedItems = unresolvedDetailFailures.size,
            reusedBooks = reusedMirrors.size
        )
    }

    private suspend fun upsertItem(
        root: LibraryRootEntity,
        serverKey: String,
        item: AbsLibraryItemDto,
        now: Long,
        syncRunId: String,
        existingSync: AbsSyncStateEntity?,
        serverVersion: String?,
        fullListFingerprint: String
    ) {
        val existingBookEntity = catalogStore.getBookById(idMapper.bookId(serverKey, requireNotNull(item.id)))
        val cachedCover = runCatching {
            coverCache?.downloadCover(root, requireNotNull(item.id))
        }.getOrNull()
        val book = catalogMapper.toBook(
            root = root,
            serverKey = serverKey,
            item = item,
            existing = existingBookEntity,
            syncedAt = now,
            coverPath = cachedCover?.originalPath ?: existingBookEntity?.coverPath,
            thumbnailPath = cachedCover?.thumbnailPath ?: existingBookEntity?.thumbnailPath,
            backgroundColorArgb = cachedCover?.backgroundColor ?: existingBookEntity?.backgroundColorArgb
        )
        val files = catalogMapper.toFiles(root, serverKey, item)
        val chapters = catalogMapper.toChapters(serverKey, item, files)
        val progress = progressMapper.toProgressOrNull(item, book, files, now)
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
            progress = progress,
            mirror = mirror,
            syncState = syncState
        )
        // 详尽中文注释：item 级 upsert 是 catalog mirror 的核心结果点，记录文件数、章节数、进度有无，可快速判断映射是否异常收缩。
        AbsSyncLogger.logUpsertItem(
            rootId = root.id,
            itemId = item.id,
            bookId = book.id,
            fileCount = files.size,
            chapterCount = chapters.size,
            hasProgress = progress != null
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
            // 详尽中文注释：REMOTE_DELETED 是最危险的收敛动作之一，必须单独记数量，方便排查误删与批量删除风险。
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
     * 详尽的中文注释：当 batch/get 失败时，退化为单 item 小批量重试。
     * 这里固定最多重试三次，三次后直接放弃当前同步轮次内的该 item，并把失败摘要记到 root 级同步状态；
     * 不额外引入新的持久化错误表，也不暴露 UI 入口，下一轮常规扫描再根据增量规则决定是否重拉。
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
                val result = runCatching {
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

    private fun minifiedFingerprint(items: List<AbsLibraryItemDto>): String =
        items.joinToString(separator = "|") { item -> "${item.id}:${item.updatedAt}" }

    private data class DetailRetryResult(
        val items: List<AbsLibraryItemDto>,
        val failures: Map<String, String>
    )

    companion object {
        private const val MAX_DETAIL_RETRY_ATTEMPTS = 3
    }
}

/**
 * 详尽的中文注释：ABS 可播放书籍的筛选门槛必须与实际入库主链保持一致。
 * 当前 BookFileEntity 的可播放文件完全来自 `media.tracks`，并依赖其中的 `contentUrl` 作为真实流地址，
 * 因此这里必须严格要求 `mediaType == book` 且 `tracks` 非空，不能再把只有 `audioFiles` 的条目误判为可播放。
 * 否则同步器会放行一个后续无法生成任何 BookFileEntity 的条目，导致镜像层出现“书已入库但无可播音轨”的语义裂缝。
 */
internal fun isAbsPlayableBook(item: AbsLibraryItemDto): Boolean =
    item.mediaType.equals("book", ignoreCase = true) &&
        (item.media?.tracks?.isNotEmpty() == true)

/**
 * 详尽的中文注释：根据上一轮全量列表指纹与每个 item 的 `remoteUpdatedAt`，裁决哪些 item 需要重拉详情。
 * 设计目标是“无变化 item 不进详情队列，但新 item 和疑似变化 item 一定进入”，这样既降低同步成本，又不破坏阶段 2 的全量正确性路径。
 */
internal fun selectAbsDetailCandidateIds(
    minifiedItems: List<AbsLibraryItemDto>,
    existingMirrors: Map<String, AbsItemMirrorEntity>,
    previousFullListFingerprint: String?,
    currentFullListFingerprint: String
): List<String> {
    val fingerprintUnchanged = previousFullListFingerprint != null && previousFullListFingerprint == currentFullListFingerprint
    return minifiedItems.mapNotNull { item ->
        val remoteItemId = item.id ?: return@mapNotNull null
        val existingMirror = existingMirrors[remoteItemId]
        if (shouldFetchAbsItemDetail(item, existingMirror, fingerprintUnchanged)) {
            remoteItemId
        } else {
            null
        }
    }
}

/**
 * 详尽的中文注释：增量详情候选的逐 item 判定规则。
 * 1. 本地还没有 mirror 的新 item，必须拉详情。
 * 2. 如果整份 minified 指纹未变化，且本地已有 mirror，则直接复用历史镜像，不重拉详情。
 * 3. 指纹变化时，再退回 `remoteUpdatedAt` 比较；任一侧缺失时间戳也按保守策略重拉。
 */
internal fun shouldFetchAbsItemDetail(
    item: AbsLibraryItemDto,
    existingMirror: AbsItemMirrorEntity?,
    fingerprintUnchanged: Boolean
): Boolean {
    if (item.id == null) return false
    if (existingMirror == null) return true
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
 * 详尽的中文注释：把多条单 item 失败聚合成 root 级简短错误摘要，供设置页显示与日志定位使用。
 * 这里故意不暴露完整远端 DTO 或堆栈，只保留失败数量与首个 item 的紧凑原因，避免错误文案膨胀。
 */
internal fun buildAbsIncrementalErrorSummary(failures: Map<String, String>): String? {
    if (failures.isEmpty()) return null
    val first = failures.entries.first()
    return "DETAIL_ITEM_FAILED:${failures.size}:first=${first.key}:${first.value}"
}

/**
 * 详尽的中文注释：同步完成后返回给上层设置页的轻量结果摘要。
 * 这里专门区分“本轮新增添加的书本数”和“仅做详情刷新/镜像复用的书本数”，
 * 这样 toast 才能准确回答用户最关心的“成功添加多少本、失败多少本”。
 */
data class AbsSyncSummary(
    val totalItems: Int,
    val addedBooks: Int,
    val syncedBooks: Int,
    val failedItems: Int,
    val reusedBooks: Int
)

data class AbsSyncPlan(
    val totalItems: Int,
    val batchSize: Int,
    val requiresConfirmation: Boolean
)
