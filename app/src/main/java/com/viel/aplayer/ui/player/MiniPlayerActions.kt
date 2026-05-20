package com.viel.aplayer.ui.player


data class MiniPlayerActions(
    val onPlayPauseClick: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onUnavailable: () -> Unit = {},
)