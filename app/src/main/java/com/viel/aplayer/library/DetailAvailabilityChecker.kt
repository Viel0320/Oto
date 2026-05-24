package com.viel.aplayer.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.availability.AvailabilityChecker

// Detail-only availability check; rescans do not update old BookFile reachability.
class DetailAvailabilityChecker(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    // 详情页可用性检查通过统一标准件执行，保持 SAF 行为不变，同时给远程源接入预留状态模型。
    private val availabilityChecker = AvailabilityChecker(context.applicationContext)

    suspend fun check(bookId: String): DetailAvailabilityResult = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            // No AUDIO files means playback cannot be built for this book.
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return@withContext DetailAvailabilityResult(
                isAvailable = false,
                bookStatus = AudiobookSchema.BookStatus.UNAVAILABLE,
                readyAudioCount = 0,
                missingAudioCount = 0
            )
        }

        // 为每一次改动添加详尽的中文注释：详情页可用性检查按目录批量执行，多文件书籍只枚举各父目录一次，并将状态按 READY/MISSING 两批写库。
        val availabilityByFileId = availabilityChecker.checkBookFiles(files)
        val readyFileIds = mutableListOf<String>()
        val missingFileIds = mutableListOf<String>()
        files.forEach { file ->
            if (availabilityByFileId[file.id]?.isAvailable == true) {
                readyFileIds.add(file.id)
            } else {
                missingFileIds.add(file.id)
            }
        }
        if (readyFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(readyFileIds, AudiobookSchema.FileStatus.READY)
        }
        if (missingFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(missingFileIds, AudiobookSchema.FileStatus.MISSING)
        }

        val readyCount = readyFileIds.size
        val missingCount = missingFileIds.size

        val bookStatus = when {
            readyCount == 0 -> AudiobookSchema.BookStatus.UNAVAILABLE
            missingCount > 0 -> AudiobookSchema.BookStatus.PARTIAL
            else -> AudiobookSchema.BookStatus.READY
        }
        // Detail checks only affect this book's availability state.
        bookDao.updateBookStatus(bookId, bookStatus)

        DetailAvailabilityResult(
            isAvailable = readyCount > 0,
            bookStatus = bookStatus,
            readyAudioCount = readyCount,
            missingAudioCount = missingCount
        )
    }

}

data class DetailAvailabilityResult(
    val isAvailable: Boolean,
    val bookStatus: String,
    val readyAudioCount: Int,
    val missingAudioCount: Int
)
