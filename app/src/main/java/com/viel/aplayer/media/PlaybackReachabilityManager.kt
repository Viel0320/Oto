package com.viel.aplayer.media

import android.content.Context
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.library.availability.AvailabilityChecker
import kotlinx.coroutines.delay

/**
 * 详尽 of 中文注释：专门负责播放运行期音频文件物理就绪度存在性校验、就绪降级重算、
 * 以及下一首就绪音频查找检索的物理可达性管理器。
 * 本组件从原 LibraryRepository 中彻底解耦，消除复杂的物理降级与查找重算算法给主体数据存取类带来的复杂性耦合。
 */
class PlaybackReachabilityManager(
    context: Context,
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao
) {
    // 播放运行期可达性统一进入 AvailabilityChecker，后续 WebDAV 播放探测可在同一标准件内扩展。
    private val availabilityChecker = AvailabilityChecker(context.applicationContext)

    /**
     * 播放期可达性按本地/非本地分层；本地单次快判，非本地留出网络缓冲重试窗口。
     */
    private suspend fun checkFileAvailable(file: BookFileEntity): Boolean {
        val root = libraryRootDao.getRootById(file.rootId) ?: return false
        val isLocal = root.sourceType == AudiobookSchema.LibrarySourceType.SAF
        return if (isLocal) {
            availabilityChecker.checkBookFile(file).isAvailable
        } else {
            checkRemoteFileAvailableWithGrace(file)
        }
    }

    private suspend fun checkRemoteFileAvailableWithGrace(file: BookFileEntity): Boolean {
        // 非本地来源可能受网络抖动影响，连续失败后才允许播放层降级或跳过。
        REMOTE_REACHABILITY_RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
            if (availabilityChecker.checkBookFile(file).isAvailable) return true
            if (attempt < REMOTE_REACHABILITY_RETRY_DELAYS_MS.lastIndex) delay(delayMs)
        }
        return false
    }

    /**校验当前有声书的恢复进度对应的音频文件在物理上是否可读就绪。
     * 若已就绪，保存就绪状态，若已丢失，降级该文件及整本书的就绪级别（如降级为 PARTIAL/UNAVAILABLE）。
     */
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            // 无音频行说明此书不可播放，强制设为 UNAVAILABLE
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return false
        }

        val progress = bookDao.getProgressForBookSync(bookId)
        val targetFile = resolveProgressFile(progress, files) ?: files.first()
        val isReady = checkFileAvailable(targetFile)
        updatePlaybackFileStatus(targetFile, isReady)
        recalculatePlaybackBookStatus(bookId)
        return isReady
    }

    /**在播放器遇到音频缓冲/准备播放错误时，物理标记具体的排队音频行状态为 MISSING 丢失，并重算整本书可达性。
     */
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) {
        val files = bookDao.getFilesForBookList(bookId)
        files.getOrNull(queueIndex)?.let { failedFile ->
            // 播放器报错后本地文件立即降级，非本地文件先走缓冲重试，避免网络瞬断导致误标 MISSING。
            if (checkFileAvailable(failedFile)) {
                bookDao.updateBookFileStatus(failedFile.id, AudiobookSchema.FileStatus.READY)
            } else {
                bookDao.updateBookFileStatus(failedFile.id, AudiobookSchema.FileStatus.MISSING)
            }
            recalculatePlaybackBookStatus(bookId)
        }
    }

    /**在当前正在播放的文件发生不可达异常时，检索列表中下一个可播放（READY）的音频文件，
     * 从而规避死循环准备失败，为 PlaybackService 提供可靠的自动跳过能力。
     */
    suspend fun findNextAvailablePlaybackFile(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return null
        }

        val candidateFiles = files.drop(afterQueueIndex + 1)
        if (!allLocalFiles(candidateFiles)) {
            return findNextAvailableRemoteAware(bookId, afterQueueIndex, files)
        }
        // 播放器错误后查找下一轨时，对剩余分轨做一次批量 VFS 可达性检查，避免多文件书籍逐轨重复枚举同一目录。
        val availabilityByFileId = availabilityChecker.checkBookFiles(candidateFiles)
        val readyFileIds = mutableListOf<String>()
        val missingFileIds = mutableListOf<String>()
        var nextAvailable: Pair<Int, BookFileEntity>? = null
        candidateFiles.forEachIndexed { offset, candidate ->
            val isReady = availabilityByFileId[candidate.id]?.isAvailable == true
            if (isReady) {
                readyFileIds.add(candidate.id)
            } else {
                missingFileIds.add(candidate.id)
            }
            if (isReady) {
                val queueIndex = afterQueueIndex + 1 + offset
                if (nextAvailable == null) nextAvailable = queueIndex to candidate
            }
        }
        if (readyFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(readyFileIds, AudiobookSchema.FileStatus.READY)
        }
        if (missingFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(missingFileIds, AudiobookSchema.FileStatus.MISSING)
        }

        recalculatePlaybackBookStatus(bookId)
        return nextAvailable
    }

    private suspend fun allLocalFiles(files: List<BookFileEntity>): Boolean {
        // 播放期批量快检只应用于本地 SAF；非本地来源仍走带网络缓冲时间的单文件策略。
        val sourceTypesByRootId = files.map { it.rootId }.distinct().associateWith { rootId ->
            libraryRootDao.getRootById(rootId)?.sourceType
        }
        return files.all { file -> sourceTypesByRootId[file.rootId] == AudiobookSchema.LibrarySourceType.SAF }
    }

    private suspend fun findNextAvailableRemoteAware(
        bookId: String,
        afterQueueIndex: Int,
        files: List<BookFileEntity>
    ): Pair<Int, BookFileEntity>? {
        for (queueIndex in (afterQueueIndex + 1)..files.lastIndex) {
            val candidate = files[queueIndex]
            val isReady = checkFileAvailable(candidate)
            updatePlaybackFileStatus(candidate, isReady)
            if (isReady) {
                recalculatePlaybackBookStatus(bookId)
                return queueIndex to candidate
            }
        }
        recalculatePlaybackBookStatus(bookId)
        return null
    }

    /**私有方法，将特定的进度模型对象映射到关联的物理音频行中。
     */
    fun resolveProgressFile(progress: BookProgressEntity?, files: List<BookFileEntity>): BookFileEntity? {
        if (progress == null) return null
        return progress.bookFileId?.let { id -> files.firstOrNull { it.id == id } }
            ?: files.getOrNull(progress.currentFileIndex)
            ?: files.getOrNull(PositionMapper.globalToFilePosition(progress.globalPositionMs, files).first)
    }

    /**更新单个音频物理文件的就绪状态（READY/MISSING）。
     */
    private suspend fun updatePlaybackFileStatus(file: BookFileEntity, isReady: Boolean) {
        val status = if (isReady) AudiobookSchema.FileStatus.READY else AudiobookSchema.FileStatus.MISSING
        bookDao.updateBookFileStatus(file.id, status)
    }

    /**物理重算并回写某本有声书在库中的就绪级别（READY、PARTIAL、UNAVAILABLE）。
     */
    private suspend fun recalculatePlaybackBookStatus(bookId: String) {
        val files = bookDao.getFilesForBookList(bookId)
        bookDao.updateBookStatus(bookId, playbackBookStatusFromFiles(files))
    }

    /**通过某本书所有音频物理行的状态判定其书籍状态级别。
     */
    private fun playbackBookStatusFromFiles(files: List<BookFileEntity>): String {
        val readyCount = files.count { it.status == AudiobookSchema.FileStatus.READY }
        val missingCount = files.count { it.status == AudiobookSchema.FileStatus.MISSING }
        return when {
            files.isEmpty() || readyCount == 0 -> AudiobookSchema.BookStatus.UNAVAILABLE
            missingCount > 0 -> AudiobookSchema.BookStatus.PARTIAL
            else -> AudiobookSchema.BookStatus.READY
        }
    }

    private companion object {
        // 非本地播放可达性最多尝试三次，总等待约 2.3 秒，给 WebDAV 等网络源短暂恢复窗口。
        val REMOTE_REACHABILITY_RETRY_DELAYS_MS = longArrayOf(800L, 1_500L, 0L)
    }
}
