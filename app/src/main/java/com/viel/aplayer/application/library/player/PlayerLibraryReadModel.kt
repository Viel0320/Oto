package com.viel.aplayer.application.library.player

import com.viel.aplayer.application.library.LibraryAnchorStatus
import com.viel.aplayer.application.library.LibraryChapterSource
import com.viel.aplayer.media.subtitle.SubtitleLine
import kotlinx.coroutines.flow.Flow

/**
 * Player Chapter Item (Room-free chapter projection for playback UI)
 * Carries timeline and availability fields needed by player controls without exposing Room relation models.
 */
data class PlayerChapterItem(
    val id: String,
    val bookId: String,
    val bookFileId: String,
    val index: Int,
    val title: String,
    val startPositionMs: Long,
    val durationMs: Long,
    val fileOffsetMs: Long,

    val source: LibraryChapterSource,
    val isFileMissing: Boolean = false
)

/**
 * Player Bookmark Item (Room-free bookmark projection for playback UI)
 * Preserves stable anchor fields so the player adapter can update or delete bookmarks without leaking the database entity upward.
 */
data class PlayerBookmarkItem(
    val id: String,
    val bookId: String,
    val globalPositionMs: Long,
    val bookFileId: String? = null,
    val fileOffsetMs: Long = 0L,
    val fileFingerprint: String? = null,

    val anchorStatus: LibraryAnchorStatus = LibraryAnchorStatus.OK,
    val title: String,
    val createdAt: Long
)

/**
 * Player Related Book (Room-free recommendation row for the player scene)
 * Contains only the fields rendered by the related-books panel and the id needed to start playback.
 */
data class PlayerRelatedBook(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val totalDurationMs: Long,
    val thumbnailPath: String?,
    val coverPath: String?,
    val coverLastUpdated: Long,
    val progressPercent: Int
)

/**
 * Player Related Section (Recommendation group projection)
 * Keeps author and narrator grouping local to the player scene instead of reusing data-layer progress rows.
 */
data class PlayerRelatedSection(
    val name: String,
    val books: List<PlayerRelatedBook>
)

/**
 * Player Related Data (Full related-books panel projection)
 * Bundles same-author, same-narrator, recent, and heuristic rows as player-scene types.
 */
data class PlayerRelatedData(
    val authorSections: List<PlayerRelatedSection> = emptyList(),
    val narratorSections: List<PlayerRelatedSection> = emptyList(),
    val recentlyAdded: List<PlayerRelatedBook> = emptyList(),
    val heuristicRecommended: List<PlayerRelatedBook> = emptyList()
)

/**
 * Player Chapter Timeline (Timeline helpers over player-scene chapter projections)
 * Keeps chapter math in the player boundary so UI code does not convert projections back into Room entities.
 */
object PlayerChapterTimeline {
    fun sorted(chapters: List<PlayerChapterItem>): List<PlayerChapterItem> =
        chapters.sortedWith(compareBy<PlayerChapterItem> { it.startPositionMs }.thenBy { it.index })

    fun currentChapter(chapters: List<PlayerChapterItem>, positionMs: Long): PlayerChapterItem? {
        return sorted(chapters).lastOrNull { positionMs >= it.startPositionMs }
    }

    fun start(chapter: PlayerChapterItem?): Long = chapter?.startPositionMs ?: 0L

    fun duration(
        chapters: List<PlayerChapterItem>,
        chapter: PlayerChapterItem?,
        totalDurationMs: Long
    ): Long {
        if (chapter == null) return totalDurationMs.coerceAtLeast(1L)
        val sortedChapters = sorted(chapters)
        val index = sortedChapters.indexOfFirst { it.id == chapter.id }
        val nextStart = sortedChapters.getOrNull(index + 1)?.startPositionMs
        return when {
            nextStart != null -> (nextStart - chapter.startPositionMs).coerceAtLeast(1L)
            chapter.durationMs > 0L -> chapter.durationMs
            totalDurationMs > chapter.startPositionMs -> totalDurationMs - chapter.startPositionMs
            else -> totalDurationMs.coerceAtLeast(1L)
        }
    }

    fun positionInChapter(
        chapters: List<PlayerChapterItem>,
        chapter: PlayerChapterItem?,
        positionMs: Long,
        totalDurationMs: Long
    ): Long {
        val chapterStart = start(chapter)
        val chapterDuration = duration(chapters, chapter, totalDurationMs)
        return (positionMs - chapterStart).coerceIn(0L, chapterDuration)
    }

    fun currentIndex(chapters: List<PlayerChapterItem>, chapter: PlayerChapterItem?): Int {
        if (chapter == null) return -1
        return sorted(chapters).indexOfFirst { it.id == chapter.id }
    }
}

/**
 * Player Library Metadata (Scene-level playback metadata projection)
 * Combines the selected book row, chapter rows, bookmark rows, and subtitle rows so PlayerViewModel receives one stable metadata stream.
 */
data class PlayerLibraryMetadata(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val narrator: String = "",
    val coverPath: String? = null,
    val thumbnailPath: String? = null,
    val coverLastUpdated: Long = 0L,
    val chapters: List<PlayerChapterItem> = emptyList(),
    val subtitles: List<SubtitleLine> = emptyList(),
    val bookmarks: List<PlayerBookmarkItem> = emptyList()
)

/**
 * Player Restored Progress Snapshot (Cold-start compact-player progress projection)
 * Carries only the book identity and global position needed to restore UI preview state without exposing persistence entities to PlayerViewModel.
 */
data class PlayerRestoredProgressSnapshot(
    val bookId: String,
    val positionMs: Long
)

/**
 * Player Book Preview (Cold-start duration projection)
 * Supplies the compact-player duration value needed for visual preview restoration without requiring a full book entity in the UI layer.
 */
data class PlayerBookPreview(
    val bookId: String,
    val durationMs: Long
)

/**
 * Player Library Read Model (Player-scene read and refresh surface)
 * Groups playback-page metadata, subtitle loading, cover polling, progress restoration, related reads, and current-track availability behind one narrow interface.
 */
interface PlayerLibraryReadModel {
    /**
     * Observe Metadata (Selected-book metadata stream)
     * Emits one player-scoped metadata projection assembled from the underlying book, chapter, bookmark, and subtitle sources.
     */
    fun observeMetadata(
        bookId: String,
        subtitles: Flow<List<SubtitleLine>>
    ): Flow<PlayerLibraryMetadata>

    /**
     * Related Data Stream (Recommendation read model)
     * Keeps related-catalog lookups inside the player scene module instead of constructing recommendation use cases in PlayerViewModel.
     */
    fun relatedData(
        bookId: String,
        author: String,
        narrator: String
    ): Flow<PlayerRelatedData>

    /**
     * Last Played Snapshot (Cold-start progress lookup)
     * Returns the persisted playback checkpoint needed to show the compact player after app startup.
     */
    suspend fun getLastPlayedSnapshot(): PlayerRestoredProgressSnapshot?

    /**
     * Book Preview Lookup (Cold-start book duration lookup)
     * Returns only the duration data required to restore preview progress for a known book id.
     */
    suspend fun getBookPreview(bookId: String): PlayerBookPreview?

    /**
     * Subtitle Load (Active-file subtitle lookup)
     * Loads external subtitle cues for the currently selected playback file through the player scene boundary.
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine>

    /**
     * Display Cover Lookup (Cover polling projection)
     * Returns the thumbnail-first cover path used after playback plan loading without exposing the whole book entity to helper classes.
     */
    suspend fun findDisplayCoverPath(bookId: String): String?

    /**
     * Current Playback Availability Refresh (Active-track reachability check)
     * Revalidates the current playback file status and returns whether the compact player can remain attached to this book.
     */
    suspend fun refreshCurrentPlaybackAvailability(bookId: String): Boolean
}
