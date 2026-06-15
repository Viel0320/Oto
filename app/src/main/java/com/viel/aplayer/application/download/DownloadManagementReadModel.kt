package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.DownloadMetadataDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class ManualDownloadTaskItem(
    val bookId: String,
    val title: String,
    val author: String,
    val thumbnailPath: String?,
    val coverPath: String?,
    val updatedAt: Long,
    val cacheStatus: BookCacheStatus
)

interface DownloadManagementReadModel {
    /**
     * Observe Manual Download Tasks (Return only user-requested offline cache rows)
     * The management UI lists durable manual download aggregates and never treats memory buffering as a task queue.
     */
    fun observeManualDownloadTasks(): Flow<List<ManualDownloadTaskItem>>
}

class RoomDownloadManagementReadModel(
    private val downloadMetadataDao: DownloadMetadataDao,
    private val bookDao: BookDao
) : DownloadManagementReadModel {
    override fun observeManualDownloadTasks(): Flow<List<ManualDownloadTaskItem>> =
        combine(
            downloadMetadataDao.observeAllMetadata(),
            bookDao.getAllBooks()
        ) { metadataRows, books ->
            val booksById = books.associateBy { book -> book.id }
            metadataRows.map { metadata ->
                val book = booksById[metadata.bookId]
                // Manual Download Task Projection (Join durable cache state with lightweight book display data)
                // Missing book rows fall back to the stable book id so stale metadata remains visible until cleanup reconciles it.
                ManualDownloadTaskItem(
                    bookId = metadata.bookId,
                    title = book?.title?.takeIf { title -> title.isNotBlank() } ?: metadata.bookId,
                    author = book?.author.orEmpty(),
                    thumbnailPath = book?.thumbnailPath,
                    coverPath = book?.coverPath,
                    updatedAt = metadata.updatedAt,
                    cacheStatus = metadata.toBookCacheStatus()
                )
            }
        }
}
