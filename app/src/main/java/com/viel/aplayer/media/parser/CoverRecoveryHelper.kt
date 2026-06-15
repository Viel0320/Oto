package com.viel.aplayer.media.parser

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.sync.AbsCoverStore
import com.viel.aplayer.abs.sync.AbsItemMirrorDao
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.logger.ScanWorkflowLogger
import com.viel.aplayer.media.manifest.ManifestSidecarSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * 负责有声书封面缓存丢失后的异步自愈。
 *
 * 从这次收口开始，这个类不再直接调用任何格式专属的封面解析器。
 * 它只做三件事：
 * 1. 判断缓存是否丢失
 * 2. 按优先级尝试“内嵌封面 -> sidecar 图片 -> 其他音频内嵌封面”
 * 3. 把 parser 产出的封面字节落到本地缓存并回写数据库
 */
@UnstableApi
class CoverRecoveryHelper(
    private val context: Context,
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao,
    private val coverExtractor: CoverExtractor,
    private val scope: CoroutineScope,
    private val fileReader: VfsFileInterface,
    private val absItemMirrorDao: AbsItemMirrorDao,
    private val absCoverStoreProvider: () -> AbsCoverStore?
) {
    // 封面重建会触发 VFS 读流、图片解码和主色提取，因此继续用信号量限制全局并发。
    private val regenerationSemaphore = Semaphore(MAX_CONCURRENT_COVER_REGENERATIONS)
    // 所有内嵌封面字节都统一通过 MetadataResolver 向各格式 parser 请求，
    // 复用构造时已注入的运行期 VFS 单例，避免 MetadataResolver 再独立获取 AppDatabase。
    private val MetadataResolver = MetadataResolver(fileReader)

    private val pendingRegenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /**
     * Track Attempted Failures: Stores book IDs that failed recovery mapped to the timestamp of the last attempt.
     * This avoids repeated, CPU-intensive background regeneration jobs for books without embedded or sidecar covers.
     */
    private val alreadyAttempted = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // 详尽的中文注释：列表、搜索和最近播放这些 Flow 会在短时间内重复把同一本书送进 checkCovers()，
    // 如果每次都同步执行 File.exists()，就会把本来已经存在的封面也反复打到磁盘层。
    // 这里用“书籍 id + 当前封面路径 + 当前缩略图路径 + lastScannedAt”做一个秒级短窗口缓存，
    // 只复用很短时间内刚探测过的结果，不引入新的持久化状态，也不会改变丢失封面的自愈语义。
    private val coverPresenceCache = java.util.concurrent.ConcurrentHashMap<String, CoverPresenceSnapshot>()

    fun checkAndTriggerCoverRegeneration(book: BookEntity) {
        val bookId = book.id
        if (alreadyAttempted.containsKey(bookId)) return
        if (pendingRegenerations.contains(bookId)) return

        // 详尽的中文注释：封面存在性探测现在先走短窗口缓存。
        // 这样同一本书在连续重组或多个列表投影里被重复检查时，绝大多数“封面本来就存在”的情况
        // 都能直接复用最近一次判断结果，避免把 File.exists() 变成高频同步磁盘噪音。
        val presence = resolveCoverPresence(book)
        if (!presence.isCoverLost && !presence.isThumbnailLost) return

        if (pendingRegenerations.add(bookId)) {
            scope.launch(Dispatchers.IO) {
                var rebuiltCover = false
                try {
                    regenerationSemaphore.withPermit {
                        rebuiltCover = regenerateCoverForBook(bookId)
                    }
                } catch (error: Exception) {
                    ScanWorkflowLogger.error("coverRecovery background regenerate failed: bookId=$bookId", error)
                } finally {
                    pendingRegenerations.remove(bookId)
                    if (rebuiltCover) {
                        alreadyAttempted.remove(bookId)
                    } else {
                        val nowElapsedMs = SystemClock.elapsedRealtime()
                        alreadyAttempted[bookId] = nowElapsedMs
                        pruneAlreadyAttempted(nowElapsedMs)
                    }
                }
            }
        }
    }

    suspend fun forceRegenerateCover(bookId: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            alreadyAttempted.remove(bookId)
            try {
                regenerationSemaphore.withPermit {
                    regenerateCoverForBook(bookId)
                }
            } catch (error: Exception) {
                ScanWorkflowLogger.error("coverRecovery force regenerate failed: bookId=$bookId", error)
                false
            }
        }

    /**
     * Regenerates the cover artwork for a specified audiobook.
     *
     * This function supports two main flows based on the book's source type:
     * 1. For [AudiobookSchema.SourceType.ABS_REMOTE], it downloads the cover artwork directly
     *    from the remote Audiobookshelf server using the lazy [AbsCoverStore].
     * 2. For local books, it attempts to extract embedded cover artwork from the audio files,
     *    falling back to sidecar images if it is a multi-file book.
     */
    private suspend fun regenerateCoverForBook(bookId: String): Boolean {
        val book = bookDao.getBookById(bookId) ?: return false
        if (book.sourceType == AudiobookSchema.SourceType.ABS_REMOTE) {
            // Self-heal ABS Remote Cover (Trigger cover download from remote ABS server)
            // ABS remote books do not have local media files for embedded/sidecar cover extraction, so we fetch their covers from the remote ABS server.
            val root = libraryRootDao.getRootById(book.rootId) ?: return false
            val mirror = absItemMirrorDao.getByLocalBookId(bookId) ?: return false
            val coverStore = absCoverStoreProvider() ?: return false
            val finalCoverResult = coverStore.downloadCover(root, mirror.remoteItemId)
            if (!finalCoverResult.hasImage()) return false
            bookDao.updateCoverPaths(
                id = bookId,
                coverPath = finalCoverResult.originalPath,
                thumbnailPath = finalCoverResult.thumbnailPath,
                lastScannedAt = System.currentTimeMillis()
            )
            return true
        }
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) return false

        val primaryFile = files.firstOrNull { file -> file.status == AudiobookSchema.FileStatus.READY } ?: files.first()
        var finalCoverResult = tryExtractEmbeddedCover(bookId, primaryFile)

        if (!finalCoverResult.hasImage() && book.sourceType != AudiobookSchema.SourceType.SINGLE_AUDIO) {
            // 单音频书籍现在明确禁止 sidecar 封面，
            // 因此封面自愈阶段只允许 manifest/聚合等多文件书继续尝试同目录 sidecar 图片。
            finalCoverResult = tryExtractSidecarCover(primaryFile)
        }

        if (!finalCoverResult.hasImage()) {
            for (fallbackFile in files.drop(1)) {
                finalCoverResult = tryExtractEmbeddedCover(bookId, fallbackFile)
                if (finalCoverResult.hasImage()) break
            }
        }

        if (!finalCoverResult.hasImage()) return false

        // 封面缓存一旦重建成功，就顺带更新 lastScannedAt，
        // 这样 Room Flow 会触发 UI 重新取图，不需要额外的手工刷新信号。
        bookDao.updateCoverPaths(
            id = bookId,
            coverPath = finalCoverResult.originalPath,
            thumbnailPath = finalCoverResult.thumbnailPath,
            // Deprecated: backgroundColorArgb is removed from parameter list
            lastScannedAt = System.currentTimeMillis()
        )
        return true
    }

    private suspend fun tryExtractEmbeddedCover(
        bookId: String,
        file: BookFileEntity
    ): CoverExtractor.CoverResult =
        try {
            val embeddedCover = MetadataResolver.extractWithEmbeddedCover(file).embeddedCover
            if (embeddedCover == null || embeddedCover.bytes.isEmpty()) {
                CoverExtractor.CoverResult(null, null)
            } else {
                // 这里不再区分 covr / APIC / PICTURE / METADATA_BLOCK_PICTURE，
                // 它们都已经在各自 parser 内部解析成统一的 `embeddedCover.bytes`。
                coverExtractor.saveEmbeddedImage(
                    "$bookId:${file.rootId}:${file.sourcePath}:embedded",
                    embeddedCover.bytes
                )
            }
        } catch (error: Exception) {
            ScanWorkflowLogger.error("coverRecovery embedded cover parse failed: bookId=$bookId", error)
            CoverExtractor.CoverResult(null, null)
        }

    private suspend fun tryExtractSidecarCover(primaryFile: BookFileEntity): CoverExtractor.CoverResult =
        try {
            val sidecar = findSidecarImage(primaryFile) ?: return CoverExtractor.CoverResult(null, null)
            coverExtractor.processExternalImage("${sidecar.root.id}:${sidecar.metadata.sourcePath}") {
                fileReader.open(sidecar)
            }
        } catch (error: Exception) {
            ScanWorkflowLogger.error("coverRecovery sidecar parse failed: sourcePath=${primaryFile.sourcePath}", error)
            CoverExtractor.CoverResult(null, null)
        }

    private fun CoverExtractor.CoverResult.hasImage(): Boolean =
        originalPath != null || thumbnailPath != null

    private suspend fun findSidecarImage(file: BookFileEntity): com.viel.aplayer.library.vfs.VfsNode? {
        val parentPath = file.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val files = fileReader.listChildren(file.rootId, parentPath)
            .filter { node -> !node.metadata.isDirectory && isImage(node.metadata.displayName) }
        val selectedRef = ManifestSidecarSupport.findDirectoryCover(
            files.map { node ->
                // 封面恢复阶段把 VfsNode 先规整成 FileRef，
                // 然后直接复用全项目统一的 sidecover 选择规则，避免这里再维护一份并行优先级列表。
                FileRef(
                    rootId = node.root.id,
                    sourcePath = node.metadata.sourcePath,
                    sourceIdentity = node.metadata.identity,
                    etag = node.metadata.etag,
                    parentSourcePath = node.metadata.parentSourcePath,
                    parentSourceKey = "${node.root.id}:${node.metadata.parentSourcePath}",
                    parentSourceIdentity = node.metadata.parentIdentity,
                    displayName = node.metadata.displayName,
                    fileSize = node.metadata.fileSize,
                    lastModified = node.metadata.lastModified
                )
            }
        ) ?: return null
        return files.firstOrNull { node -> node.metadata.sourcePath == selectedRef.sourcePath }
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp")
    }

    /**
     * 详尽的中文注释：在秒级短窗口内复用最近一次封面存在性探测结果。
     * key 中包含 lastScannedAt，因此一旦封面重建成功或手动更换封面，下一次检查会自然落到新 key，
     * 不会因为旧缓存而继续误判“封面仍然缺失”。
     */
    private fun resolveCoverPresence(book: BookEntity): CoverPresenceState {
        val cacheKey = buildCoverPresenceCacheKey(book)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val cachedSnapshot = coverPresenceCache[cacheKey]
        if (cachedSnapshot != null && nowElapsedMs - cachedSnapshot.checkedAtElapsedMs <= COVER_PRESENCE_CACHE_TTL_MS) {
            return CoverPresenceState(
                isCoverLost = cachedSnapshot.isCoverLost,
                isThumbnailLost = cachedSnapshot.isThumbnailLost
            )
        }

        val presence = CoverPresenceState(
            isCoverLost = book.coverPath == null || !File(book.coverPath).exists(),
            isThumbnailLost = book.thumbnailPath == null || !File(book.thumbnailPath).exists()
        )
        coverPresenceCache[cacheKey] = CoverPresenceSnapshot(
            checkedAtElapsedMs = nowElapsedMs,
            isCoverLost = presence.isCoverLost,
            isThumbnailLost = presence.isThumbnailLost
        )
        pruneExpiredCoverPresenceSnapshots(nowElapsedMs)
        return presence
    }

    /**
     * 详尽的中文注释：缓存 key 直接绑定到“当前这份封面状态快照”。
     * 只要路径或 lastScannedAt 任何一项变化，就说明 UI 后续看到的是另一轮封面事实，
     * 这时必须重新做一次物理存在性探测，而不是沿用上一轮缓存判断。
     */
    private fun buildCoverPresenceCacheKey(book: BookEntity): String =
        "${book.id}|${book.coverPath.orEmpty()}|${book.thumbnailPath.orEmpty()}|${book.lastScannedAt}"

    /**
     * 详尽的中文注释：短窗口缓存只为压掉瞬时重复 I/O，不应该无限增长。
     * 因此当缓存项超过上限时，只清理已经过期的旧条目，保留最近仍可能被多次复用的判断结果。
     */
    /**
     * Cap Cover Presence Cache: Evicts expired presence entries first.
     * If the cache size still exceeds the hard limit, prunes the oldest active entries by access timestamp.
     */
    private fun pruneExpiredCoverPresenceSnapshots(nowElapsedMs: Long) {
        val iterator = coverPresenceCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowElapsedMs - entry.value.checkedAtElapsedMs > COVER_PRESENCE_CACHE_TTL_MS) {
                iterator.remove()
            }
        }
        if (coverPresenceCache.size > MAX_COVER_PRESENCE_CACHE_SIZE) {
            val sortedEntries = coverPresenceCache.entries.sortedBy { it.value.checkedAtElapsedMs }
            val excessCount = coverPresenceCache.size - MAX_COVER_PRESENCE_CACHE_SIZE
            for (i in 0 until excessCount) {
                if (i < sortedEntries.size) {
                    coverPresenceCache.remove(sortedEntries[i].key)
                }
            }
        }
    }

    /**
     * Cap Already Attempted Failures: Limits the in-memory record size of failed recovery book IDs.
     * Keeps memory consumption bounded by removing the oldest failure attempt records when exceeding the limit.
     */
    private fun pruneAlreadyAttempted(nowElapsedMs: Long) {
        if (alreadyAttempted.size <= MAX_ALREADY_ATTEMPTED_SIZE) return
        val sortedEntries = alreadyAttempted.entries.sortedBy { it.value }
        val excessCount = alreadyAttempted.size - MAX_ALREADY_ATTEMPTED_SIZE
        for (i in 0 until excessCount) {
            if (i < sortedEntries.size) {
                alreadyAttempted.remove(sortedEntries[i].key)
            }
        }
    }

    /**
     * 详尽的中文注释：把最近一次封面存在性判断压缩成一个极小的内存态结果对象，
     * 既能表达“原图是否丢失”和“缩略图是否丢失”，又不会把 File 对象或更多运行期状态带进缓存里。
     */
    private data class CoverPresenceState(
        val isCoverLost: Boolean,
        val isThumbnailLost: Boolean
    )

    /**
     * 详尽的中文注释：缓存快照只保存结果和检测时刻。
     * 使用 elapsed realtime 记录检测时间可以避开系统时间被用户修改后导致 TTL 判断跳变的问题。
     */
    private data class CoverPresenceSnapshot(
        val checkedAtElapsedMs: Long,
        val isCoverLost: Boolean,
        val isThumbnailLost: Boolean
    )

    private companion object {
        private const val MAX_CONCURRENT_COVER_REGENERATIONS = 2
        private const val COVER_PRESENCE_CACHE_TTL_MS = 3_000L
        private const val MAX_COVER_PRESENCE_CACHE_SIZE = 512
        private const val MAX_ALREADY_ATTEMPTED_SIZE = 1024
    }
}

