package com.viel.aplayer.ui.settings


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
    /** Fullscreen player visibility (To control whether the full player screen is displayed) */
    val isFullPlayerVisible: Boolean = false
)