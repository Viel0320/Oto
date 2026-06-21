package com.viel.aplayer.abs.mapping

import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.timeline.PositionMapper

class AbsProgressMapper {
    fun toProgressOrNull(
        item: AbsLibraryItemDto,
        book: BookEntity,
        files: List<BookFileEntity>,
        syncedAt: Long
    ): BookProgressEntity? {
        val remote = item.progress ?: return null
        return toProgress(remote, book, files, syncedAt)
    }

    /**
     * Converts ABS user progress into the local Room progress model.
     * This mapper is shared by catalog sync and direct progress probes so both flows use identical unit conversion and file anchoring.
     */
    fun toProgress(
        remote: AbsUserProgressDto,
        book: BookEntity,
        files: List<BookFileEntity>,
        syncedAt: Long
    ): BookProgressEntity {
        val globalPositionMs = (resolvedCurrentTimeSec(remote) * 1000.0).toLong().coerceAtLeast(0L)
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

    fun toReadStatus(item: AbsLibraryItemDto, existing: BookEntity?): AudiobookSchema.ReadStatus =
        RemoteProgressReadStatusPolicy.fromRemoteProgress(
            isFinished = item.progress?.isFinished,
            hasPositivePosition = item.progress?.let(::resolvedCurrentTimeSec)
                ?.let { positionSec -> positionSec > 0.0 } == true,
            existingReadStatus = existing?.readStatus
        )

    /**
     * Reconstructs seconds when ABS omits currentTime but includes progress ratio and duration.
     * This keeps first-sync read status and local progress deterministic across authorize and item-detail payload shapes.
     */
    fun resolvedCurrentTimeSec(remote: AbsUserProgressDto): Double =
        remote.currentTime ?: remote.progress?.let { ratio ->
            remote.duration?.let { totalDurationSec -> ratio * totalDurationSec }
        } ?: 0.0

    private data class ProgressAnchor(
        val bookFileId: String?,
        val currentFileIndex: Int,
        val positionInFileMs: Long
    )
}
