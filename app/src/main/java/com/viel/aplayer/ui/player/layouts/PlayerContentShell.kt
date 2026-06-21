package com.viel.aplayer.ui.player.layouts

/**
 * Names the adaptive player content slots shared by player layouts.
 *
 * Keeping these slot indices in the layouts package lets bookmarks, controls, and related
 * recommendations share transition targets without duplicate private enum declarations.
 */
enum class PlayerContentShell(val index: Int) {
    Bookmarks(0),
    PlaybackShell(1),
    Related(2)
}
