package com.viel.aplayer.ui.player.components.bookmarks

import com.viel.aplayer.application.library.player.PlayerBookmarkItem

data class BookmarkActions(
    val onDelete: (PlayerBookmarkItem) -> Unit = {},
    val onUpdate: (PlayerBookmarkItem, String) -> Unit = { _, _ -> },
    val onShowDialog: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    /*
     * Bookmark Edit Action Triggers (Expose editing dialog interactions in Actions)
     * Maps user events for bookmark deletion requests, edit requests, editing title changes, and dismissing editing dialogs to the centralized actions aggregate.
     */
    val onRequestDelete: (PlayerBookmarkItem) -> Unit = {},
    val onRequestEdit: (PlayerBookmarkItem) -> Unit = {},
    val onEditTitleChange: (String) -> Unit = {},
    val onDismissDialogs: () -> Unit = {},
)
