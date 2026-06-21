package com.viel.aplayer.ui.player.components.bookmarks

import com.viel.aplayer.application.library.player.PlayerBookmarkItem

data class BookmarkActions(
    val onDelete: (PlayerBookmarkItem) -> Unit = {},
    val onUpdate: (PlayerBookmarkItem, String) -> Unit = { _, _ -> },
    val onShowDialog: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRequestDelete: (PlayerBookmarkItem) -> Unit = {},
    val onRequestEdit: (PlayerBookmarkItem) -> Unit = {},
    val onEditTitleChange: (String) -> Unit = {},
    val onDismissDialogs: () -> Unit = {},
)
