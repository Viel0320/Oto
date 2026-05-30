package com.viel.aplayer.ui.player.components.bookmarks

import com.viel.aplayer.data.entity.BookmarkEntity

data class BookmarkActions(
    val onDelete: (BookmarkEntity) -> Unit = {},
    val onUpdate: (BookmarkEntity, String) -> Unit = { _, _ -> },
    val onShowDialog: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
)