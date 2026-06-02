package com.viel.aplayer.abs.mapping

import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.media.PositionMapper

class AbsProgressMapper {
    fun toProgressOrNull(
        item: AbsLibraryItemDto,
        book: BookEntity,
        files: List<BookFileEntity>,
        syncedAt: Long
    ): BookProgressEntity? {
        val remote = item.progress ?: return null
        val globalPositionMs = ((remote.currentTime ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
        val anchor = if (files.isNotEmpty()) {
            val (fileIndex, posInFile) = PositionMapper.globalToFilePosition(globalPositionMs, files)
            val file = files.getOrNull(fileIndex)
            ProgressAnchor(file?.id, fileIndex, posInFile)
        } else {
            ProgressAnchor(null, 0, 0L)
        }
        return BookProgressEntity(
            bookId = book.id,
            globalPositionMs = globalPositionMs,
            bookFileId = anchor.bookFileId,
            currentFileIndex = anchor.currentFileIndex,
            positionInFileMs = anchor.positionInFileMs,
            anchorStatus = AudiobookSchema.AnchorStatus.OK,
            lastPlayedAt = remote.lastUpdate ?: syncedAt
        )
    }

    fun toReadStatus(item: AbsLibraryItemDto, existing: BookEntity?): String =
        when {
            item.progress?.isFinished == true -> AudiobookSchema.ReadStatus.FINISHED
            (item.progress?.currentTime ?: 0.0) > 0.0 -> AudiobookSchema.ReadStatus.IN_PROGRESS
            else -> existing?.readStatus ?: AudiobookSchema.ReadStatus.NOT_STARTED
        }

    private data class ProgressAnchor(
        val bookFileId: String?,
        val currentFileIndex: Int,
        val positionInFileMs: Long
    )
}
