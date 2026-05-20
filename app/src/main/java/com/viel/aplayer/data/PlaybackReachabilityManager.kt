package com.viel.aplayer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.playback.PositionMapper
import java.io.File

/**
 * 详尽 of 中文注释：专门负责播放运行期音频文件物理就绪度存在性校验、就绪降级重算、
 * 以及下一首就绪音频查找检索的物理可达性管理器。
 * 本组件从原 LibraryRepository 中彻底解耦，消除复杂的物理降级与查找重算算法给主体数据存取类带来的复杂性耦合。
 */
class PlaybackReachabilityManager(
    private val context: Context,
    private val bookDao: BookDao
) {

    /**
     * 详尽 of 中文注释：轻量级检查给定 URI 的物理文件在本地或 SAF 内容提供者中是否存在。
     * 支持 "content" 协议（SAF Single Document）与 "file" 协议（传统绝对路径）。
     */
    fun checkFileExists(uriString: String): Boolean {
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == "content") {
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.exists() == true
            } else {
                File(uri.path ?: "").exists()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 详尽 of 中文注释：校验当前有声书的恢复进度对应的音频文件在物理上是否可读就绪。
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
        val isReady = checkFileExists(targetFile.uri)
        updatePlaybackFileStatus(targetFile, isReady)
        recalculatePlaybackBookStatus(bookId)
        return isReady
    }

    /**
     * 详尽 of 中文注释：在播放器遇到音频缓冲/准备播放错误时，物理标记具体的排队音频行状态为 MISSING 丢失，并重算整本书可达性。
     */
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) {
        val files = bookDao.getFilesForBookList(bookId)
        files.getOrNull(queueIndex)?.let { failedFile ->
            bookDao.updateBookFileStatus(failedFile.id, AudiobookSchema.FileStatus.MISSING)
            recalculatePlaybackBookStatus(bookId)
        }
    }

    /**
     * 详尽 of 中文注释：在当前正在播放的文件发生不可达异常时，检索列表中下一个可播放（READY）的音频文件，
     * 从而规避死循环准备失败，为 PlaybackService 提供可靠的自动跳过能力。
     */
    suspend fun findNextAvailablePlaybackFile(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return null
        }

        for (queueIndex in (afterQueueIndex + 1)..files.lastIndex) {
            val candidate = files[queueIndex]
            val isReady = checkFileExists(candidate.uri)
            updatePlaybackFileStatus(candidate, isReady)
            if (isReady) {
                recalculatePlaybackBookStatus(bookId)
                return queueIndex to candidate
            }
        }

        recalculatePlaybackBookStatus(bookId)
        return null
    }

    /**
     * 详尽 of 中文注释：私有方法，将特定的进度模型对象映射到关联的物理音频行中。
     */
    fun resolveProgressFile(progress: BookProgressEntity?, files: List<BookFileEntity>): BookFileEntity? {
        if (progress == null) return null
        return progress.bookFileId?.let { id -> files.firstOrNull { it.id == id } }
            ?: files.getOrNull(progress.currentFileIndex)
            ?: files.getOrNull(PositionMapper.globalToFilePosition(progress.globalPositionMs, files).first)
    }

    /**
     * 详尽 of 中文注释：更新单个音频物理文件的就绪状态（READY/MISSING）。
     */
    private suspend fun updatePlaybackFileStatus(file: BookFileEntity, isReady: Boolean) {
        val status = if (isReady) AudiobookSchema.FileStatus.READY else AudiobookSchema.FileStatus.MISSING
        bookDao.updateBookFileStatus(file.id, status)
    }

    /**
     * 详尽 of 中文注释：物理重算并回写某本有声书在库中的就绪级别（READY、PARTIAL、UNAVAILABLE）。
     */
    private suspend fun recalculatePlaybackBookStatus(bookId: String) {
        val files = bookDao.getFilesForBookList(bookId)
        bookDao.updateBookStatus(bookId, playbackBookStatusFromFiles(files))
    }

    /**
     * 详尽 of 中文注释：通过某本书所有音频物理行的状态判定其书籍状态级别。
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
}
