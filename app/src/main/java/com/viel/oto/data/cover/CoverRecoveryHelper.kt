package com.viel.oto.data.cover

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.abs.sync.AbsCoverStore
import com.viel.oto.data.abs.sync.AbsItemMirrorDao
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.library.FileRef
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.logger.ScanWorkflowLogger
import com.viel.oto.media.manifest.ManifestSidecarSupport
import com.viel.oto.media.parser.CoverExtractor
import com.viel.oto.media.parser.MetadataResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Asynchronously repairs missing audiobook cover cache files.
 *
 * The helper stays format-agnostic: it detects missing cache files, retries embedded and sidecar
 * artwork through shared parser boundaries, and writes recovered paths back to Room.
 */
@OptIn(UnstableApi::class)
class CoverRecoveryHelper(
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao,
    private val coverExtractor: CoverExtractor,
    private val scope: CoroutineScope,
    private val fileReader: VfsFileInterface,
    private val absItemMirrorDao: AbsItemMirrorDao,
    private val absCoverStoreProvider: () -> AbsCoverStore?
) : CoverSelfHealer {
    /**
     * Limits cover repair concurrency because each job may read VFS streams, decode bitmaps, and sample colors.
     */
    private val regenerationSemaphore = Semaphore(MAX_CONCURRENT_COVER_REGENERATIONS)

    /**
     * Reuses the injected runtime VFS when asking format parsers for embedded artwork bytes.
     */
    private val metadataResolver = MetadataResolver(fileReader)

    private val pendingRegenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /**
     * Track Attempted Failures: Stores book IDs that failed recovery mapped to the timestamp of the last attempt.
     * This avoids repeated, CPU-intensive background regeneration jobs for books without embedded or sidecar covers.
     */
    private val alreadyAttempted = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /**
     * Reuses short-lived cover presence checks across list, search, and recent-played Flow bursts.
     */
    private val coverPresenceCache = java.util.concurrent.ConcurrentHashMap<String, CoverPresenceSnapshot>()

    override fun checkAndTriggerCoverRegeneration(book: BookEntity) =
        checkAndTriggerCoverRegeneration(
            bookId = book.id,
            coverPath = book.coverPath,
            thumbnailPath = book.thumbnailPath,
            lastScannedAt = book.lastScannedAt
        )

    override fun checkAndTriggerCoverRegeneration(
        bookId: String,
        coverPath: String?,
        thumbnailPath: String?,
        lastScannedAt: Long
    ) {
        if (alreadyAttempted.containsKey(bookId)) return
        if (pendingRegenerations.contains(bookId)) return

        val presence = resolveCoverPresence(bookId, coverPath, thumbnailPath, lastScannedAt)
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
                        pruneAlreadyAttempted()
                    }
                }
            }
        }
    }

    override suspend fun forceRegenerateCover(bookId: String): Boolean =
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
     *    from the remote AudiobookShelf server using the lazy [AbsCoverStore].
     * 2. For local books, it attempts to extract embedded cover artwork from the audio files,
     *    falling back to sidecar images if it is a multi-file book.
     */
    private suspend fun regenerateCoverForBook(bookId: String): Boolean {
        val book = bookDao.getBookById(bookId) ?: return false
        if (book.sourceType == AudiobookSchema.SourceType.ABS_REMOTE) {
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
            finalCoverResult = tryExtractSidecarCover(primaryFile)
        }

        if (!finalCoverResult.hasImage()) {
            for (fallbackFile in files.drop(1)) {
                finalCoverResult = tryExtractEmbeddedCover(bookId, fallbackFile)
                if (finalCoverResult.hasImage()) break
            }
        }

        if (!finalCoverResult.hasImage()) return false

        bookDao.updateCoverPaths(
            id = bookId,
            coverPath = finalCoverResult.originalPath,
            thumbnailPath = finalCoverResult.thumbnailPath,
            lastScannedAt = System.currentTimeMillis()
        )
        return true
    }

    private suspend fun tryExtractEmbeddedCover(
        bookId: String,
        file: BookFileEntity
    ): CoverExtractor.CoverResult =
        try {
            val embeddedCover = metadataResolver.extractWithEmbeddedCover(file).embeddedCover
            if (embeddedCover == null || embeddedCover.bytes.isEmpty()) {
                CoverExtractor.CoverResult(null, null)
            } else {
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

    private suspend fun findSidecarImage(file: BookFileEntity): com.viel.oto.library.vfs.VfsNode? {
        val parentPath = file.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val files = fileReader.listChildren(file.rootId, parentPath)
            .filter { node -> !node.metadata.isDirectory && isImage(node.metadata.displayName) }
        val selectedRef = ManifestSidecarSupport.findDirectoryCover(
            files.map { node ->
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
     * Reuses the latest physical cover presence check within a short time window.
     *
     * The key includes [lastScannedAt], so successful recovery or manual cover replacement
     * naturally invalidates the cached result.
     */
    private fun resolveCoverPresence(
        bookId: String,
        coverPath: String?,
        thumbnailPath: String?,
        lastScannedAt: Long
    ): CoverPresenceState {
        val cacheKey = buildCoverPresenceCacheKey(bookId, coverPath, thumbnailPath, lastScannedAt)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val cachedSnapshot = coverPresenceCache[cacheKey]
        if (cachedSnapshot != null && nowElapsedMs - cachedSnapshot.checkedAtElapsedMs <= COVER_PRESENCE_CACHE_TTL_MS) {
            return CoverPresenceState(
                isCoverLost = cachedSnapshot.isCoverLost,
                isThumbnailLost = cachedSnapshot.isThumbnailLost
            )
        }

        val presence = CoverPresenceState(
            isCoverLost = coverPath == null || !File(coverPath).exists(),
            isThumbnailLost = thumbnailPath == null || !File(thumbnailPath).exists()
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
     * Binds the cache key to the current cover path, thumbnail path, and scan timestamp snapshot.
     */
    private fun buildCoverPresenceCacheKey(
        bookId: String,
        coverPath: String?,
        thumbnailPath: String?,
        lastScannedAt: Long
    ): String =
        "$bookId|${coverPath.orEmpty()}|${thumbnailPath.orEmpty()}|$lastScannedAt"

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
    private fun pruneAlreadyAttempted() {
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
     * Minimal in-memory result for the latest cover and thumbnail presence check.
     */
    private data class CoverPresenceState(
        val isCoverLost: Boolean,
        val isThumbnailLost: Boolean
    )

    /**
     * Stores a presence result and monotonic check time so wall-clock changes do not affect TTL checks.
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

