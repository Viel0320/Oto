package com.viel.aplayer.ui.action

import com.viel.aplayer.data.AudiobookEntity

data class ContentActions(
    val onSelectedTabChange: (Int) -> Unit = {},
    val onShowChapterList: () -> Unit = {},
    val onDismissChapterList: () -> Unit = {},
    val onLoadRelatedBook: (AudiobookEntity) -> Unit = {},
    val onToggleProgressMode: () -> Unit = {},
)
