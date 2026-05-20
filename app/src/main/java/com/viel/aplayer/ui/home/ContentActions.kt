package com.viel.aplayer.ui.home

import com.viel.aplayer.data.entity.BookWithProgress

data class ContentActions(
    val onSelectedTabChange: (Int) -> Unit = {},
    val onShowChapterList: () -> Unit = {},
    val onDismissChapterList: () -> Unit = {},
    val onLoadRelatedBook: (BookWithProgress) -> Unit = {},
    val onToggleProgressMode: () -> Unit = {},
    val onDeleteBook: () -> Unit = {},
)