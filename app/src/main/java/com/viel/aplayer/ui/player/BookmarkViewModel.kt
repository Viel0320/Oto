package com.viel.aplayer.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Create BookmarkViewModel (Manages bookmarks list updates and dialog overlays)
// This ViewModel encapsulates all add, edit, delete, and dialog editing states for bookmarks.
class BookmarkViewModel(
    application: Application,
    rawExternalScope: CoroutineScope? = null
) : AndroidViewModel(application) {

    // Shift scope to viewModelScope to prevent lifecycle leaks and screen rotation freezes
    // Fall back to viewModelScope if rawExternalScope is null to ensure coroutines are correctly managed.
    private val externalScope = rawExternalScope ?: viewModelScope

    // Resolve dependencies (Fetches bookmark scene commands and app-event sink from presentation graph)
    private val playerDependencies = APlayerApplication.getPlayerScreenDependencies(application)
    private val bookmarkCommands = playerDependencies.playerBookmarkCommands
    private val appEventSink = playerDependencies.appEventSink

    private val bookmarkManager = BookmarkManager(bookmarkCommands, externalScope)

    // Bookmark dialogs state definition (Holds active edit and delete confirmation dialog structures)
    data class BookmarkDialogsState(
        val toDelete: PlayerBookmarkItem? = null,
        val toEdit: PlayerBookmarkItem? = null,
        val editTitle: String = ""
    )

    private val _bookmarkDialogs = MutableStateFlow(BookmarkDialogsState())
    val bookmarkDialogs: StateFlow<BookmarkDialogsState> = _bookmarkDialogs.asStateFlow()

    // Request bookmark deletion (Pushes bookmark delete target to show confirmation modal dialog)
    fun requestDeleteBookmark(b: PlayerBookmarkItem) {
        _bookmarkDialogs.update { it.copy(toDelete = b) }
    }

    // Request bookmark editing (Pushes edit target and prefills edit text title fields)
    fun requestEditBookmark(b: PlayerBookmarkItem) {
        _bookmarkDialogs.update { it.copy(toEdit = b, editTitle = b.title) }
    }

    // Update edit text (Syncs title text updates to the active edit state)
    fun onBookmarkEditTitleChange(t: String) {
        _bookmarkDialogs.update { it.copy(editTitle = t) }
    }

    // Dismiss active bookmark dialogs (Wipes active bookmark edit buffers)
    fun dismissBookmarkDialogs() {
        _bookmarkDialogs.value = BookmarkDialogsState()
    }

    fun deleteBookmark(bookmark: PlayerBookmarkItem) {
        bookmarkManager.deleteBookmark(bookmark)
    }

    fun updateBookmark(bookmark: PlayerBookmarkItem, newTitle: String) {
        bookmarkManager.updateBookmark(bookmark, newTitle)
    }

    fun addBookmark(bookId: String, position: Long, title: String) {
        bookmarkManager.addBookmark(bookId, position, title)
    }

    // Save bookmark from dialog (Adds bookmark with name or resource default title and notifies user)
    fun saveBookmarkFromDialog(bookId: String, position: Long, title: String) {
        addBookmark(bookId, position, title.ifBlank { defaultBookmarkTitle() })
        dismissBookmarkDialogs()
        appEventSink.showToast(FeedbackMessages.playbackBookmarkCreated())
    }

    private fun defaultBookmarkTitle(): String {
        return getApplication<Application>().getString(com.viel.aplayer.R.string.bookmark_default_title)
    }
}
