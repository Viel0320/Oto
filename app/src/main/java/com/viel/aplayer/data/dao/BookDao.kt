package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.media.PositionMapper
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    // UI lists hide soft-deleted books while their BookFile claims stay reserved.
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY title ASC")
    fun getAllBooks(): Flow<List<BookEntity>>

    // 详尽的中文注释：新增一次性获取全部未逻辑删除的书籍实体的挂起查询，用于后台同步任务直接获取数据快照判定空库状态，解耦对 Flow 流的物理消费。
    @Query("SELECT * FROM books WHERE status != 'DELETED'")
    suspend fun getAllBooksOnce(): List<BookEntity>

    // UI lists hide soft-deleted books while their BookFile claims stay reserved.
    @Transaction
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY title ASC")
    fun getAllBooksWithProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBookById(id: String): Flow<BookEntity?>

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateBookStatus(id: String, status: String)

    @Query("UPDATE books SET lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateBookLastScannedAt(id: String, lastScannedAt: Long)

    // Rescan builds ExistingClaimIndex from all persisted file ownership rows.
    @Query("SELECT * FROM book_files")
    suspend fun getAllBookFilesOnce(): List<BookFileEntity>

    // Cold-start light scans only re-check previously missing audio rows, not every old file.
    @Query("""
        SELECT book_files.* FROM book_files
        INNER JOIN books ON books.id = book_files.bookId
        WHERE books.status != 'DELETED'
        AND book_files.fileRole = 'AUDIO'
        AND book_files.status = 'MISSING'
        ORDER BY book_files.bookId ASC, book_files.`index` ASC
    """)
    suspend fun getMissingAudioBookFilesOnce(): List<BookFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("UPDATE books SET backgroundColorArgb = :color WHERE id = :id")
    suspend fun updateBackgroundColor(id: String, color: Int)

    @Transaction
    @Query("""
        SELECT * FROM books
        WHERE status != 'DELETED'
        AND (
            (title LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (title = '' OR title IS NULL)))
            OR (author LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (author = '' OR author IS NULL)))
            OR (narrator LIKE '%' || :query || '%' OR (:query = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        )
        ORDER BY title ASC
    """)
    fun searchBooksWithProgress(query: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (year LIKE '%' || :year || '%' OR (:year = 'Unknown' AND (year = '' OR year IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByYearWithProgress(year: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByAuthorWithProgress(author: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        ORDER BY title ASC
    """)
    fun filterByNarratorWithProgress(narrator: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (author LIKE '%' || :author || '%' OR (:author = 'Unknown' AND (author = '' OR author IS NULL)))
        AND id != :excludeId
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun filterByAuthorLimitedWithProgress(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND (narrator LIKE '%' || :narrator || '%' OR (:narrator = 'Unknown' AND (narrator = '' OR narrator IS NULL)))
        AND id != :excludeId
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun filterByNarratorLimitedWithProgress(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    // Recently added UI excludes soft-deleted books.
    @Transaction
    @Query("SELECT * FROM books WHERE status != 'DELETED' ORDER BY addedAt DESC LIMIT :limit")
    fun getRecentlyAddedWithProgress(limit: Int): Flow<List<BookWithProgress>>

    @Transaction
    @Query("""
        SELECT * FROM books 
        WHERE status != 'DELETED'
        AND id != :currentId
        AND author NOT IN (:authors) 
        AND narrator NOT IN (:narrators) 
        ORDER BY addedAt DESC LIMIT :limit
    """)
    fun getRecentlyAddedExclusiveWithProgress(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>>

    // BookFile rows include both SOURCE_MANIFEST and AUDIO ownership facts.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookFiles(files: List<BookFileEntity>)

    // Playback-facing file flow must exclude SOURCE_MANIFEST rows.
    @Query("SELECT * FROM book_files WHERE bookId = :bookId AND fileRole = 'AUDIO' ORDER BY `index` ASC")
    fun getFilesForBook(bookId: String): Flow<List<BookFileEntity>>

    // Playback and progress mapping use only AUDIO files.
    @Query("SELECT * FROM book_files WHERE bookId = :bookId AND fileRole = 'AUDIO' ORDER BY `index` ASC")
    suspend fun getFilesForBookList(bookId: String): List<BookFileEntity>

    // 获取书籍的所有物理关联文件，包括音频与清单（SOURCE_MANIFEST），不带 fileRole 过滤，专门用于详情页识别源文件名
    @Query("SELECT * FROM book_files WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getAllFilesForBookList(bookId: String): List<BookFileEntity>

    // 播放和字幕链路现在都以 MediaItem.mediaId 中的 BookFileEntity.id 为稳定入口，不再从播放 URI 反查旧文件行。
    @Query("SELECT * FROM book_files WHERE id = :id AND fileRole = 'AUDIO' LIMIT 1")
    suspend fun getBookFileById(id: String): BookFileEntity?

    @Query("UPDATE book_files SET status = :status, lastSeenScanId = :scanId WHERE id = :id")
    suspend fun updateBookFileStatus(id: String, status: String, scanId: String? = null)

    // 详情页和恢复流程会批量刷新多文件书籍的可用状态，用 IN 更新避免逐文件 Room 写入拖慢 UI。
    @Query("UPDATE book_files SET status = :status, lastSeenScanId = :scanId WHERE id IN (:ids)")
    suspend fun updateBookFileStatuses(ids: List<String>, status: String, scanId: String? = null)

    // BookProgress is created only when playback/seek/save actually happens.
    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    fun getProgressForBook(bookId: String): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    suspend fun getProgressForBookSync(bookId: String): BookProgressEntity?

    // 应用冷启动恢复逻辑。
    // 仅检索最后一次播放、且尚未完成（进度未达 99%）的非删除书籍进度。
    // 这样可以确保用户下次进入应用时，迷你播放器不会加载已经播放完毕的书籍。
    @Query("""
        SELECT book_progress.* FROM book_progress
        INNER JOIN books ON books.id = book_progress.bookId
        WHERE books.status != 'DELETED'
        AND (books.totalDurationMs = 0 OR book_progress.globalPositionMs < (books.totalDurationMs * 0.99))
        ORDER BY book_progress.lastPlayedAt DESC
        LIMIT 1
    """)
    suspend fun getLastPlayedProgressSync(): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: BookProgressEntity)

    @Query("UPDATE books SET title = :title, author = :author, narrator = :narrator, description = :description, totalDurationMs = :duration WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, author: String, narrator: String, description: String, duration: Long)

    // 根据书籍ID更新书籍的详细元数据，包括标题、作者、讲述人、描述与年份，便于“书籍信息修改器”进行统一编辑与保存
    @Query("UPDATE books SET title = :title, author = :author, narrator = :narrator, description = :description, year = :year WHERE id = :id")
    suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String)

    // 专门用于当缓存被清理丢失后，后台重新提取并局部更新书籍封面的物理缓存路径、背景主色调与最新扫描时间戳。
    // 使用局部 UPDATE 避免覆盖其他并发更新的字段，防止引发多线程写入竞态。此外，通过更新 lastScannedAt 强制让 Flow 重发以触发布局重绘自动刷新。
    @Query("UPDATE books SET coverPath = :coverPath, thumbnailPath = :thumbnailPath, backgroundColorArgb = :backgroundColorArgb, lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateCoverPaths(id: String, coverPath: String?, thumbnailPath: String?, backgroundColorArgb: Int?, lastScannedAt: Long)

    // 根据书库根目录ID查询该书库下所有的书籍实体，专用于在书库被删除释放权限时，安全地物理清理关联的封面和缩略图物理缓存文件，避免造成文件垃圾残留
    @Query("SELECT * FROM books WHERE rootId = :rootId")
    suspend fun getBooksByRootId(rootId: String): List<BookEntity>

    // 根据书籍 ID 更新书籍的阅读状态（未开始/进行中/已完成）到 Room 数据库中，用于响应长按菜单状态修改和播放位置自动更新
    @Query("UPDATE books SET readStatus = :readStatus WHERE id = :id")
    suspend fun updateBookReadStatus(id: String, readStatus: String)

    /**
     * 详尽的中文注释：在同一个数据库写事务内，原子化执行进度读取、映射计算、进度落盘以及阅读状态联动更新。
     * 本方法采用 Room 核心的 @Transaction 机制，将原本分散在应用服务层的多步读写动作聚合为原子事务，
     * 从而在底层彻底规避高频进度上报时由于协程并发交错执行导致的“读-改-写”竞态数据覆盖（进度回退）缺陷，
     * 并确保在进程强杀或异常抛出时数据绝对处于一致状态。
     */
    @Transaction
    suspend fun updateProgressWithReadStatus(bookId: String, position: Long, currentTime: Long) {
        val progress = getProgressForBookSync(bookId)
        val files = getFilesForBookList(bookId)
        
        if (files.isNotEmpty()) {
            val (fileIndex, posInFile) = PositionMapper.globalToFilePosition(position, files)
            val bookFileId = files.getOrNull(fileIndex)?.id

            val updated = progress?.copy(
                globalPositionMs = position,
                bookFileId = bookFileId,
                currentFileIndex = fileIndex,
                positionInFileMs = posInFile,
                lastPlayedAt = currentTime
            ) ?: BookProgressEntity(
                bookId = bookId,
                globalPositionMs = position,
                bookFileId = bookFileId,
                currentFileIndex = fileIndex,
                positionInFileMs = posInFile,
                anchorStatus = AudiobookSchema.AnchorStatus.OK,
                lastPlayedAt = currentTime
            )
            insertProgress(updated)
        } else if (progress != null) {
            insertProgress(progress.copy(
                globalPositionMs = position,
                lastPlayedAt = currentTime
            ))
        }

        // 联动更新阅读状态
        val book = getBookById(bookId)
        if (book != null) {
            val nextStatus = when {
                book.totalDurationMs > 0L && position >= (book.totalDurationMs * 0.99).toLong() -> AudiobookSchema.ReadStatus.FINISHED
                position > 0L -> AudiobookSchema.ReadStatus.IN_PROGRESS
                else -> AudiobookSchema.ReadStatus.NOT_STARTED
            }
            if (book.readStatus != nextStatus) {
                updateBookReadStatus(bookId, nextStatus)
            }
        }
    }
}
