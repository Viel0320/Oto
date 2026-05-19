package com.viel.aplayer.ui.action

data class MiniPlayerActions(
    val onPlayPauseClick: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onUnavailable: () -> Unit = {},
)
