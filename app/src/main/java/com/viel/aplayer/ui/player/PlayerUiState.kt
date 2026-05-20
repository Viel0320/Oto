package com.viel.aplayer.ui.player

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.ui.settings.PlayerSettingsState

/**
 * 根 UI 状态聚合类。
 * 将元数据、播放状态和设置组合在一起，作为单项数据流提供给 UI 层。
 */
data class PlayerUiState(
    /** 书籍元数据子状态 */
    val metadata: BookMetadataState = BookMetadataState(),
    /** 核心播放逻辑子状态 */
    val playback: PlaybackState = PlaybackState(),
    /** UI 表现与交互子状态 */
    val settings: PlayerSettingsState = PlayerSettingsState(),
    /** 相关作者板块列表 */
    val relatedAuthorSections: List<RelatedSection> = emptyList(),
    /** 相关播讲人板块列表 */
    val relatedNarratorSections: List<RelatedSection> = emptyList(),
    /** 最近添加/导入的书籍列表 */
    val recentlyAddedBooks: List<BookWithProgress> = emptyList()
) {
    // --- 快捷访问器：保留以下字段以保证现有 UI 代码的向后兼容性 ---
    
    /** 播放状态 */
    val isPlaying get() = playback.isPlaying
    val playWhenReady get() = playback.playWhenReady
    
    /** 基础信息 */
    val currentId get() = metadata.id
    val currentTitle get() = metadata.title
    val currentAuthor get() = metadata.author
    val currentNarrator get() = metadata.narrator
    val currentCoverPath get() = metadata.coverPath
    val currentThumbnailPath get() = metadata.thumbnailPath
    val backgroundColorArgb get() = metadata.backgroundColorArgb
    
    /** 进度与时长 */
    val currentPosition get() = playback.currentPosition
    val duration get() = playback.duration
    val progress get() = playback.progress
    val playbackSpeed get() = playback.playbackSpeed
    val isSpeedManualMode get() = playback.isSpeedManualMode
    
    /** UI 设置 */
    val selectedSleepTimer get() = settings.selectedSleepTimer
    val showUndoSeek get() = settings.showUndoSeek
    val isChapterListVisible get() = settings.isChapterListVisible
    val isBookmarkDialogVisible get() = settings.isBookmarkDialogVisible
    val bookmarkTitle get() = settings.bookmarkTitle
    val selectedContentTab get() = settings.selectedContentTab
    val isMiniPlayerHidden get() = settings.isMiniPlayerHidden
    val isChapterProgressMode get() = settings.isChapterProgressMode
    val isFullPlayerVisible get() = settings.isFullPlayerVisible
    
    /** 衍生状态 */
    val hasActiveTrack get() = metadata.hasActiveTrack
    val currentChapters get() = metadata.chapters
    val currentSubtitles get() = metadata.subtitles
    val currentBookmarks get() = metadata.bookmarks
    
    /** 
     * 实时计算当前播放位置所在的章节。
     */
    val currentChapter: ChapterEntity?
        // Shared chapter lookup keeps derived UI state aligned with progress and notifications.
        get() = ChapterTimeline.currentChapter(metadata.chapters, playback.currentPosition)

    /** 
     * 获取用于在进度条上显示的章节标记位置（0.0 - 1.0）。
     */
    val chapterMarkers: List<Float>
        get() = metadata.getChapterMarkers(playback.duration)
}