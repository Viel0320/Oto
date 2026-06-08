package com.viel.aplayer.ui.home

import com.viel.aplayer.application.library.home.HomeBookItem

data class ContentActions(
    val onSelectedTabChange: (Int) -> Unit = {},
    val onShowChapterList: () -> Unit = {},
    val onDismissChapterList: () -> Unit = {},
    val onLoadRelatedBook: (HomeBookItem) -> Unit = {},
    val onToggleProgressMode: () -> Unit = {},
    val onDeleteBook: () -> Unit = {},
)
