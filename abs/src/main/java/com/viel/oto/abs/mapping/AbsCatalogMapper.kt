package com.viel.oto.abs.mapping

import com.viel.oto.abs.net.dto.AbsAuthorDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsTrackDto
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.LibraryRootEntity

class AbsCatalogMapper(
    private val idMapper: AbsRemoteIdMapper
) {
    fun toBook(
        root: LibraryRootEntity,
        serverKey: String,
        item: AbsLibraryItemDto,
        existing: BookEntity?,
        syncedAt: Long,
        lastScannedAt: Long = existing?.lastScannedAt ?: 0L,
        coverPath: String? = existing?.coverPath,
        thumbnailPath: String? = existing?.thumbnailPath
    ): BookEntity {
        val itemId = item.id ?: throw com.viel.oto.abs.net.AbsApiError(code = "MALFORMED_ITEM", message = "item.id missing")
        val title = item.media?.metadata?.title
            ?: item.title
            ?: "Unknown"
        val author = item.authors.authorNames().ifBlank { item.media?.metadata?.authorName.orEmpty() }
        val narrator = item.media?.metadata?.narratorName.orEmpty()
        val year = item.media?.metadata?.publishedYear.orEmpty()
        val description = item.media?.metadata?.description.orEmpty()
        val series = item.media?.metadata?.seriesName.orEmpty()
        val totalDurationMs = ((item.media?.duration ?: item.media?.tracks.orEmpty().sumOf { it.duration ?: 0.0 }) * 1000.0).toLong()
        val totalFileSize = item.media?.tracks.orEmpty().sumOf { track ->
            track.metadata?.size ?: 0L
        }.takeIf { it > 0L }
            ?: item.media?.audioFiles.orEmpty().sumOf { it.size ?: 0L }
        return BookEntity(
            id = idMapper.bookId(serverKey, itemId),
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
            series = series,
            addedAt = existing?.addedAt ?: item.addedAt ?: syncedAt,
            lastScannedAt = lastScannedAt,
            status = AudiobookSchema.BookStatus.READY,
            readStatus = existing?.readStatus ?: AudiobookSchema.ReadStatus.NOT_STARTED
        )
    }

    fun toFiles(root: LibraryRootEntity, serverKey: String, item: AbsLibraryItemDto): List<BookFileEntity> {
        val itemId = item.id ?: throw com.viel.oto.abs.net.AbsApiError(code = "MALFORMED_ITEM", message = "item.id missing")
        val bookId = idMapper.bookId(serverKey, itemId)
        return item.media?.tracks.orEmpty()
            .sortedBy { it.index ?: Int.MAX_VALUE }
            .mapIndexed { fallbackIndex, track ->
                val trackIndex = track.index ?: (fallbackIndex + 1)
                val audioFile = item.media?.audioFiles.orEmpty().firstOrNull { audio -> audio.index == track.index }
                val contentUrl = track.contentUrl ?: throw com.viel.oto.abs.net.AbsApiError(code = "MALFORMED_ITEM", message = "track.contentUrl missing")
                BookFileEntity(
                    id = idMapper.bookFileId(serverKey, itemId, trackIndex),
                    bookId = bookId,
                    fileRole = AudiobookSchema.FileRole.AUDIO,
                    rootId = root.id,
                    index = fallbackIndex,
                    sourcePath = contentUrl,
                    sourceIdentity = "$itemId:$trackIndex:${contentUrl}",
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

    /**
     * Maps ABS chapter offsets onto generated local file rows.
     * Track spans bind to files by the stable mapper-generated file id first, avoiding accidental chapter drift when callers pass files in a different order.
     */
    fun toChapters(serverKey: String, item: AbsLibraryItemDto, files: List<BookFileEntity>): List<ChapterEntity> {
        if (files.isEmpty()) return emptyList()
        val tracks = item.media?.tracks.orEmpty().sortedBy { it.index ?: Int.MAX_VALUE }
        if (tracks.isEmpty()) return emptyList()
        val itemId = item.id ?: throw com.viel.oto.abs.net.AbsApiError(code = "MALFORMED_ITEM", message = "item.id missing")
        val bookId = idMapper.bookId(serverKey, itemId)
        val filesById = files.associateBy { file -> file.id }
        val filesBySourceIdentity = files.associateBy { file -> file.sourceIdentity }
        val trackSpans = tracks.mapIndexedNotNull { listIndex, track ->
            val trackIndex = track.index ?: (listIndex + 1)
            val file = resolveTrackFile(
                serverKey = serverKey,
                itemId = itemId,
                trackIndex = trackIndex,
                contentUrl = track.contentUrl,
                filesById = filesById,
                filesBySourceIdentity = filesBySourceIdentity
            ) ?: return@mapIndexedNotNull null
            val startMs = ((track.startOffset ?: trackSpanStart(tracks, listIndex)) * 1000.0).toLong()
            val durationMs = ((track.duration ?: 0.0) * 1000.0).toLong()
            TrackSpan(
                file = file,
                startMs = startMs,
                endExclusiveMs = startMs + durationMs
            )
        }
        if (trackSpans.isEmpty()) return emptyList()
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

    /**
     * Resolves the local file row for a remote track without depending on caller-provided list order.
     * Source identity remains a fallback for rows produced by this mapper before the chapter pass receives them.
     */
    private fun resolveTrackFile(
        serverKey: String,
        itemId: String,
        trackIndex: Int,
        contentUrl: String?,
        filesById: Map<String, BookFileEntity>,
        filesBySourceIdentity: Map<String, BookFileEntity>
    ): BookFileEntity? {
        val expectedFileId = idMapper.bookFileId(serverKey, itemId, trackIndex)
        return filesById[expectedFileId]
            ?: contentUrl?.let { url -> filesBySourceIdentity["$itemId:$trackIndex:$url"] }
    }

    private data class TrackSpan(
        val file: BookFileEntity,
        val startMs: Long,
        val endExclusiveMs: Long
    )
}
