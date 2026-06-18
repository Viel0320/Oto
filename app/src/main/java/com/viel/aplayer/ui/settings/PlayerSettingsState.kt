package com.viel.aplayer.ui.settings

import com.viel.aplayer.shared.settings.PlaybackSeekStepConfig

/**
 * Full Player Open Source (Separates visual transition origins)
 * Records which UI surface requested the current full-player visibility change so direct
 * playback entries cannot accidentally reuse the mini-player shared element channel.
 */
enum class FullPlayerOpenSource {
    /**
     * Direct Playback Entry (Use ordinary full-player presentation)
     * Home, Search, Detail, widget, and other non-mini commands open the player without
     * claiming a mini-player source cover or bounds transition.
     */
    Direct,

    /**
     * Mini Player Entry (Allow mini-to-player shared motion)
     * Mini-player taps and full-player minimization keep the compact player as the visual
     * source or target, enabling the dedicated mini <-> player animation only for that flow.
     */
    MiniPlayer
}


/**
 * UI setting state model (Container for UI view states)
 * Represents UI settings and transient visual states that do not affect core playback business logic.
 */
data class PlayerSettingsState(
    /** Selected sleep duration (To keep track of sleep timer in minutes; 0 represents disabled) */
    val selectedSleepTimer: Int = 0,
    /** Seek undo flag (To indicate if the seek undo banner is visible) */
    val showUndoSeek: Boolean = false,
    /** Chapter sheet visibility (To indicate if the chapter list overlay is shown) */
    val isChapterListVisible: Boolean = false,
    /** Bookmark dialog visibility (To indicate if the bookmark editor dialog is visible) */
    val isBookmarkDialogVisible: Boolean = false,
    /** Edited bookmark title (To store the text in the bookmark creation/edit input) */
    val bookmarkTitle: String = "",
    /** Detail tab index (To track the current selected tab in the detail screen bottom panel; e.g. 0 for bookmark, 1 for subtitle, 2 for related books) */
    val selectedContentTab: Int = -1,
    /** Mini-player dismissal flag (To track if the mini-player has been manually dismissed by the user) */
    val isMiniPlayerHidden: Boolean = false,
    /** Chapter progress mode flag (To toggle rendering progress relative to current chapter instead of full book) */
    val isChapterProgressMode: Boolean = false,
    /** Short seek step configuration (To render and execute rewind/forward transport controls) */
    val playbackSeekStepConfig: PlaybackSeekStepConfig = PlaybackSeekStepConfig(),
    /**
     * Full player open source (To isolate shared-element eligibility by caller)
     * This UI-only state keeps direct playback openings away from the mini-player motion channel
     * while preserving the mini <-> player animation for compact-player expand and collapse.
     */
    val fullPlayerOpenSource: FullPlayerOpenSource = FullPlayerOpenSource.Direct,
    /** Fullscreen player visibility (To control whether the full player screen is displayed) */
    val isFullPlayerVisible: Boolean = false
)
