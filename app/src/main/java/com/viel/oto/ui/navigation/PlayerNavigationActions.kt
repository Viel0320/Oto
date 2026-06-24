package com.viel.oto.ui.navigation


data class PlayerNavigationActions(
    val onMinimize: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onBookmarksClick: () -> Unit = {},
    val onSubtitlesClick: () -> Unit = {},
    val onRelatedClick: () -> Unit = {},
    val onNavigateToNewPlayer: () -> Unit = {},
)