package com.viel.aplayer.ui.player.layouts

/**
 * Adaptive player layout shared transition shell (PlayerContentShell).
 *
 * Dedicated to sharing the animation transition state of the three sub-layouts (bookmarks, playback controls, and related recommendations) within the layouts package,
 * avoiding duplicate private declaration conflicts (Redeclaration) or unnecessary scope isolation across different files.
 */
enum class PlayerContentShell(val index: Int) {
    Bookmarks(0),
    PlaybackShell(1),
    Related(2)
}
