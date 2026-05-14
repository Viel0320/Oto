package com.viel.aplayer.ui.state

import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.ui.utils.DEFAULT_COVER_BACKGROUND_ARGB

data class LibraryUiState(
    val audiobooks: List<AudiobookEntity> = emptyList()
)

data class DetailUiState(
    val book: AudiobookEntity? = null,
    val isAvailable: Boolean = false,
    val progressPercent: Int = 0,
    val backgroundColorArgb: Int = DEFAULT_COVER_BACKGROUND_ARGB
)
