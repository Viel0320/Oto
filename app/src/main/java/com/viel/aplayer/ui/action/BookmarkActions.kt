package com.viel.aplayer.ui.action

import com.viel.aplayer.data.BookmarkEntity

data class BookmarkActions(
    val onDelete: (BookmarkEntity) -> Unit = {},
    val onUpdate: (BookmarkEntity, String) -> Unit = { _, _ -> },
    val onShowDialog: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
)
