package com.viel.aplayer.application.library.player

import com.viel.aplayer.application.library.toLibraryAnchorStatus
import com.viel.aplayer.application.library.toLibraryChapterSource
import com.viel.aplayer.application.library.toSchemaAnchorStatus
import com.viel.aplayer.application.usecase.GetRelatedBooksUseCase
import com.viel.aplayer.application.usecase.RelatedBookCandidate
import com.viel.aplayer.application.usecase.RelatedData
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.data.subtitle.SubtitleGateway
import com.viel.aplayer.media.subtitle.SubtitleLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Default Player Library Module (Adapter from granular gateways to the player scene)
 * Centralizes playback-page metadata reads, bookmark commands, subtitle loading, cover polling, and active-track availability behind player-scoped seams.
 */
class DefaultPlayerLibraryModule(
    private val bookCatalogGateway: BookCatalogGateway,
    private val chapterGateway: ChapterGateway,
    private val bookmarkGateway: BookmarkGateway,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val progressGateway: ProgressGateway,
    private val subtitleGateway: SubtitleGateway,
    private val relatedBooksUseCase: GetRelatedBooksUseCase = GetRelatedBooksUseCase(bookCatalogGateway)
) : PlayerLibraryReadModel, PlayerBookmarkCommands {

    override fun observeMetadata(
        bookId: String,
        subtitles: Flow<List<SubtitleLine>>
    ): Flow<PlayerLibraryMetadata> {
        return combine(
            bookCatalogGateway.observeBookById(bookId),
            chapterGateway.getChapters(bookId),
            bookmarkGateway.getBookmarks(bookId),
            subtitles
        ) { book, chapters, bookmarks, subtitleLines ->
            // Metadata Projection Assembly (Keep player metadata fan-in inside the scene module)
            // The UI receives one stable projection while the module owns the gateway calls and missing-book fallback.
            if (book == null) {
                PlayerLibraryMetadata()
            } else {
                PlayerLibraryMetadata(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    narrator = book.narrator,
                    coverPath = book.coverPath,
                    thumbnailPath = book.thumbnailPath,
                    coverLastUpdated = book.lastScannedAt,
                    chapters = chapters.map { it.toPlayerChapterItem() },
                    subtitles = subtitleLines,
                    bookmarks = bookmarks.map { it.toPlayerBookmarkItem() }
                )
            }
        }
    }

    override fun relatedData(
        bookId: String,
        author: String,
        narrator: String
    ): Flow<PlayerRelatedData> {
        return relatedBooksUseCase(bookId, author, narrator)
            .map { related -> related.toPlayerRelatedData() }
    }

    override suspend fun getLastPlayedSnapshot(): PlayerRestoredProgressSnapshot? {
        return progressGateway.getLastPlayedProgressSync()?.let { progress ->
            PlayerRestoredProgressSnapshot(
                bookId = progress.bookId,
                positionMs = progress.globalPositionMs
            )
        }
    }

    override suspend fun getBookPreview(bookId: String): PlayerBookPreview? {
        return bookCatalogGateway.getBookById(bookId)?.let { book ->
            PlayerBookPreview(
                bookId = book.id,
                title = book.title,
                author = book.author,
                narrator = book.narrator,
                coverPath = book.coverPath,
                thumbnailPath = book.thumbnailPath,
                coverLastUpdated = book.lastScannedAt,
                durationMs = book.totalDurationMs
            )
        }
    }

    override suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> {
        return subtitleGateway.loadSubtitlesForBookFile(bookFileId)
    }

    override suspend fun findDisplayCoverPath(bookId: String): String? {
        val book = bookCatalogGateway.getBookById(bookId) ?: return null
        return book.thumbnailPath ?: book.coverPath
    }

    override suspend fun refreshCurrentPlaybackAvailability(bookId: String): Boolean {
        return bookAvailabilityGateway.refreshCurrentPlaybackFileAvailabilityStatus(bookId)
    }

    override suspend fun addBookmark(bookId: String, position: Long, title: String) {
        bookmarkGateway.addBookmark(bookId, position, title)
    }

    override suspend fun updateBookmark(bookmark: PlayerBookmarkItem, newTitle: String) {
        bookmarkGateway.updateBookmark(bookmark.toBookmarkEntity().copy(title = newTitle))
    }

    override suspend fun deleteBookmark(bookmark: PlayerBookmarkItem) {
        bookmarkGateway.deleteBookmark(bookmark.toBookmarkEntity())
    }
}

/**
 * Player Chapter Projection Mapping (Localize Room relation knowledge inside the player adapter)
 * Converts chapter/file rows into the timeline and availability fields consumed by the player UI.
 */
private fun ChapterWithBookFile.toPlayerChapterItem(): PlayerChapterItem {
    return PlayerChapterItem(
        id = chapter.id,
        bookId = chapter.bookId,
        bookFileId = chapter.bookFileId,
        index = chapter.index,
        title = chapter.title,
        startPositionMs = chapter.startPositionMs,
        durationMs = chapter.durationMs,
        fileOffsetMs = chapter.fileOffsetMs,
        source = chapter.source.toLibraryChapterSource(),
        isFileMissing = bookFile?.status == AudiobookSchema.FileStatus.MISSING
    )
}

/**
 * Player Bookmark Projection Mapping (Localize bookmark entity shape inside the player adapter)
 * Keeps UI bookmark dialogs on player-scene fields while retaining stable persistence anchors for mutations.
 */
private fun BookmarkEntity.toPlayerBookmarkItem(): PlayerBookmarkItem {
    return PlayerBookmarkItem(
        id = id,
        bookId = bookId,
        globalPositionMs = globalPositionMs,
        bookFileId = bookFileId,
        fileOffsetMs = fileOffsetMs,
        fileFingerprint = fileFingerprint,
        anchorStatus = anchorStatus.toLibraryAnchorStatus(),
        title = title,
        createdAt = createdAt
    )
}

/**
 * Bookmark Entity Rehydration (Restrict database entity reconstruction to the adapter)
 * Mutation gateways still accept BookmarkEntity, so the player command adapter rebuilds it from the scene projection.
 */
private fun PlayerBookmarkItem.toBookmarkEntity(): BookmarkEntity {
    return BookmarkEntity(
        id = id,
        bookId = bookId,
        globalPositionMs = globalPositionMs,
        bookFileId = bookFileId,
        fileOffsetMs = fileOffsetMs,
        fileFingerprint = fileFingerprint,
        anchorStatus = anchorStatus.toSchemaAnchorStatus(),
        title = title,
        createdAt = createdAt
    )
}

/**
 * Player Related Projection Mapping (Collapse application candidates into player recommendation rows)
 * Recommendation internals stay behind GetRelatedBooksUseCase while the player scene publishes only renderable row fields.
 */
private fun RelatedData.toPlayerRelatedData(): PlayerRelatedData {
    return PlayerRelatedData(
        authorSections = authorSections.map { section ->
            PlayerRelatedSection(section.name, section.books.map { it.toPlayerRelatedBook() })
        },
        narratorSections = narratorSections.map { section ->
            PlayerRelatedSection(section.name, section.books.map { it.toPlayerRelatedBook() })
        },
        recentlyAdded = recentlyAdded.map { it.toPlayerRelatedBook() },
        heuristicRecommended = heuristicRecommended.map { it.toPlayerRelatedBook() }
    )
}

/**
 * Player Related Book Mapping (Translate application candidates into player-scene rows)
 * The related panel needs only display fields and a playback id, so no data-layer entity or progress relation crosses into the player module.
 */
private fun RelatedBookCandidate.toPlayerRelatedBook(): PlayerRelatedBook {
    return PlayerRelatedBook(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        totalDurationMs = totalDurationMs,
        thumbnailPath = thumbnailPath,
        coverPath = coverPath,
        coverLastUpdated = coverLastUpdated,
        progressPercent = progressPercent
    )
}
