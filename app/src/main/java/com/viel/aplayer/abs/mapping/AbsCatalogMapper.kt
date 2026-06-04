package com.viel.aplayer.abs.mapping

import com.viel.aplayer.abs.net.dto.AbsAuthorDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsTrackDto
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity

class AbsCatalogMapper(
    private val idMapper: AbsRemoteIdMapper,
    private val progressMapper: AbsProgressMapper
) {
    fun toBook(
        root: LibraryRootEntity,
        serverKey: String,
        item: AbsLibraryItemDto,
        existing: BookEntity?,
        syncedAt: Long,
        lastScannedAt: Long = existing?.lastScannedAt ?: 0L,
        coverPath: String? = existing?.coverPath,
        thumbnailPath: String? = existing?.thumbnailPath,
        backgroundColorArgb: Int? = existing?.backgroundColorArgb
    ): BookEntity {
        val title = item.media?.metadata?.title
            ?: item.title
            ?: "Unknown"
        val author = item.authors.authorNames().ifBlank { item.media?.metadata?.authorName.orEmpty() }
        val narrator = item.media?.metadata?.narratorName.orEmpty()
        val year = item.media?.metadata?.publishedYear.orEmpty()
        val description = item.media?.metadata?.description.orEmpty()
        val totalDurationMs = ((item.media?.duration ?: item.media?.tracks.orEmpty().sumOf { it.duration ?: 0.0 }) * 1000.0).toLong()
        val totalFileSize = item.media?.tracks.orEmpty().sumOf { track ->
            track.metadata?.size ?: 0L
        }.takeIf { it > 0L }
            ?: item.media?.audioFiles.orEmpty().sumOf { it.size ?: 0L }
        return BookEntity(
            id = idMapper.bookId(serverKey, requireNotNull(item.id)),
            rootId = root.id,
            sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
            sourceRoot = root.sourceUri,
            title = title,
            author = author,
            narrator = narrator,
            description = description,
            year = year,
            totalDurationMs = totalDurationMs,
            totalFileSize = totalFileSize,
            coverPath = coverPath,
            thumbnailPath = thumbnailPath,
            backgroundColorArgb = backgroundColorArgb,
            addedAt = existing?.addedAt ?: syncedAt,
            // Cover Cache Invalidation (Prevent unnecessary UI image reload cycles)
            // Allow callers to supply an updated scan timestamp explicitly.
            // If the cover or thumbnail path has actually changed, the UI loader requests a new cache key.
            // If no changes have occurred, the existing timestamp is retained, avoiding invalidation of the local cover image cache.
            lastScannedAt = lastScannedAt,
            status = AudiobookSchema.BookStatus.READY,
            readStatus = progressMapper.toReadStatus(item, existing)
        )
    }

    fun toFiles(root: LibraryRootEntity, serverKey: String, item: AbsLibraryItemDto): List<BookFileEntity> {
        val itemId = requireNotNull(item.id)
        val bookId = idMapper.bookId(serverKey, itemId)
        return item.media?.tracks.orEmpty()
            .sortedBy { it.index ?: Int.MAX_VALUE }
            .mapIndexed { fallbackIndex, track ->
                val trackIndex = track.index ?: (fallbackIndex + 1)
                val audioFile = item.media?.audioFiles.orEmpty().firstOrNull { audio -> audio.index == track.index }
                BookFileEntity(
                    id = idMapper.bookFileId(serverKey, itemId, trackIndex),
                    bookId = bookId,
                    fileRole = AudiobookSchema.FileRole.AUDIO,
                    rootId = root.id,
                    index = fallbackIndex,
                    sourcePath = requireNotNull(track.contentUrl),
                    sourceIdentity = "$itemId:$trackIndex:${track.contentUrl}",
                    etag = null,
                    manifestEntryPath = null,
                    displayName = track.metadata?.filename
                        ?: audioFile?.metadata?.filename
                        ?: track.title
                        ?: "Track $trackIndex",
                    durationMs = ((track.duration ?: 0.0) * 1000.0).toLong(),
                    fileSize = track.metadata?.size ?: audioFile?.size ?: 0L,
                    lastModified = track.metadata?.mtimeMs ?: 0L,
                    fingerprint = null,
                    lastSeenScanId = null,
                    status = AudiobookSchema.FileStatus.READY
                )
            }
    }

    fun toChapters(serverKey: String, item: AbsLibraryItemDto, files: List<BookFileEntity>): List<ChapterEntity> {
        if (files.isEmpty()) return emptyList()
        val tracks = item.media?.tracks.orEmpty().sortedBy { it.index ?: Int.MAX_VALUE }
        if (tracks.isEmpty()) return emptyList()
        val bookId = idMapper.bookId(serverKey, requireNotNull(item.id))
        val trackSpans = tracks.mapIndexed { listIndex, track ->
            val startMs = ((track.startOffset ?: trackSpanStart(tracks, listIndex)) * 1000.0).toLong()
            val durationMs = ((track.duration ?: 0.0) * 1000.0).toLong()
            TrackSpan(
                file = files[listIndex],
                startMs = startMs,
                endExclusiveMs = startMs + durationMs
            )
        }
        return item.media?.chapters.orEmpty()
            .sortedBy { it.start ?: Double.MAX_VALUE }
            .mapIndexed { chapterIndex, chapter ->
                val startMs = ((chapter.start ?: 0.0) * 1000.0).toLong()
                val endMs = ((chapter.end ?: chapter.start ?: 0.0) * 1000.0).toLong()
                val span = trackSpans.firstOrNull { startMs >= it.startMs && startMs < it.endExclusiveMs }
                    ?: trackSpans.last()
                ChapterEntity(
                    id = "$bookId:chapter:${chapter.id ?: chapterIndex}",
                    bookId = bookId,
                    bookFileId = span.file.id,
                    index = chapterIndex,
                    title = chapter.title ?: "Chapter ${chapterIndex + 1}",
                    startPositionMs = startMs,
                    durationMs = (endMs - startMs).coerceAtLeast(0L),
                    fileOffsetMs = (startMs - span.startMs).coerceAtLeast(0L),
                    source = AudiobookSchema.ChapterSource.ABS
                )
            }
    }

    private fun List<AbsAuthorDto>?.authorNames(): String =
        this.orEmpty()
            .mapNotNull { author -> author.name?.takeIf { it.isNotBlank() } }
            .joinToString(", ")

    private fun trackSpanStart(tracks: List<AbsTrackDto>, index: Int): Double =
        tracks.take(index).sumOf { track -> track.duration ?: 0.0 }

    private data class TrackSpan(
        val file: BookFileEntity,
        val startMs: Long,
        val endExclusiveMs: Long
    )
}
