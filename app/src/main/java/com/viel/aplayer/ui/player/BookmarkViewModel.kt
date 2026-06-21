package com.viel.aplayer.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.event.feedback.BookManagementFeedbackFacts
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns bookmark dialog state while delegating bookmark mutations to the player bookmark command boundary.
 */
class BookmarkViewModel(
    application: Application,
    rawExternalScope: CoroutineScope? = null
) : AndroidViewModel(application) {

    private val externalScope = rawExternalScope ?: viewModelScope

    private val playerDependencies = APlayerApplication.getPlayerScreenDependencies(application)
    private val bookmarkCommands = playerDependencies.playerBookmarkCommands
    private val appEventSink = playerDependencies.appEventSink

    private val bookmarkManager = BookmarkManager(bookmarkCommands, externalScope)

    /**
     * Captures the currently active bookmark confirmation or edit draft.
     */
    data class BookmarkDialogsState(
        val toDelete: PlayerBookmarkItem? = null,
        val toEdit: PlayerBookmarkItem? = null,
        val editTitle: String = ""
    )

    private val _bookmarkDialogs = MutableStateFlow(BookmarkDialogsState())
    val bookmarkDialogs: StateFlow<BookmarkDialogsState> = _bookmarkDialogs.asStateFlow()

    fun requestDeleteBookmark(b: PlayerBookmarkItem) {
        _bookmarkDialogs.update { it.copy(toDelete = b) }
    }

    fun requestEditBookmark(b: PlayerBookmarkItem) {
        _bookmarkDialogs.update { it.copy(toEdit = b, editTitle = b.title) }
    }

    fun onBookmarkEditTitleChange(t: String) {
        _bookmarkDialogs.update { it.copy(editTitle = t) }
    }

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

    fun saveBookmarkFromDialog(bookId: String, position: Long, title: String) {
        addBookmark(bookId, position, title.ifBlank { defaultBookmarkTitle() })
        dismissBookmarkDialogs()
        appEventSink.emitFeedback(BookManagementFeedbackFacts.bookmarkCreated(bookId))
    }

    private fun defaultBookmarkTitle(): String {
        return getApplication<Application>().getString(com.viel.aplayer.R.string.bookmark_default_title)
    }
}
