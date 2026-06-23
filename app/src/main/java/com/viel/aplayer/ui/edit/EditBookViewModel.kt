package com.viel.aplayer.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.application.library.edit.EditBookCommands
import com.viel.aplayer.application.library.edit.EditBookDraft
import com.viel.aplayer.application.library.edit.EditBookReadModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Metadata editing state and persistence route.
 *
 * Keeps book loading, overlay visibility, and save operations outside the overlay shell so the
 * edit route can own state while EditBookScreen remains a stateless rendering surface.
 */
class EditBookViewModel(
    private val editBookReadModel: EditBookReadModel,
    private val editBookCommands: EditBookCommands
) : ViewModel() {

    private val _bookState = MutableStateFlow<EditBookDraft?>(null)
    val bookState = _bookState.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    private val _isSaving = MutableStateFlow(false)

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess = _saveSuccess.asStateFlow()

    /**
     * Initialize edit target and overlay visibility.
     *
     * Loads the requested book before showing the route so the screen can render either a loading state
     * or the resolved metadata without owning repository access.
     */
    fun startEdit(bookId: String) {
        _saveSuccess.value = false
        loadBook(bookId)
        setVisible(true)
    }

    /**
     * Visibility control and state reset.
     *
     * Closing the route clears cached book state to prevent stale metadata flicker on the next edit session.
     */
    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
        if (!visible) {
            _bookState.value = null
        }
    }

    /**
     * Asynchronous edit read-model query.
     *
     * Reads the editable book entity through the edit scene module so route code stays off broad library dependencies.
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            _bookState.value = editBookReadModel.getEditableBook(bookId)
        }
    }

    /**
     * Metadata persistence and cover self-healing.
     *
     * Persists edited metadata and optional custom cover changes, then lets the route close the overlay
     * through the supplied completion callback.
     */
    fun saveBook(
        title: String,
        author: String,
        narrator: String,
        year: String,
        description: String,
        series: String,
        newCoverUri: String?
    ) {
        val currentBook = _bookState.value ?: return
        viewModelScope.launch {
            _isSaving.value = true
            _saveSuccess.value = false
            try {
                editBookCommands.updateBookDetails(
                    id = currentBook.id,
                    title = title.trim(),
                    author = author.trim(),
                    narrator = narrator.trim(),
                    description = description.trim(),
                    year = year.trim(),
                    series = series.trim()
                )
                if (newCoverUri != null) {
                    editBookCommands.saveCustomCover(currentBook.id, newCoverUri)
                }
                _saveSuccess.value = true
            } catch (error: IllegalArgumentException) {
                if (error.message == "EDIT_TITLE_REQUIRED") {
                    return@launch
                }
                throw error
            } finally {
                _isSaving.value = false
            }
        }
    }
}
