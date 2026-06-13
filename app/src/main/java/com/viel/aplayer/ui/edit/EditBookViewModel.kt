package com.viel.aplayer.ui.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.application.library.edit.EditBookCommands
import com.viel.aplayer.application.library.edit.EditBookDraft
import com.viel.aplayer.application.library.edit.EditBookReadModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Edit Book ViewModel (Metadata editing state and persistence route)
 *
 * Keeps book loading, overlay visibility, and save operations outside the overlay shell so the
 * edit route can own state while EditBookScreen remains a stateless rendering surface.
 */
class EditBookViewModel(application: Application) : AndroidViewModel(application) {
    // Edit Scene Dependency View (Resolve only editable metadata reads and save commands)
    // EditBookViewModel loads and saves book metadata without receiving settings, playback, worker, or broad library dependencies.
    private val editDependencies = APlayerApplication.getEditScreenDependencies(application)
    private val editBookReadModel: EditBookReadModel = editDependencies.editBookReadModel
    private val editBookCommands: EditBookCommands = editDependencies.editBookCommands

    private val _bookState = MutableStateFlow<EditBookDraft?>(null)
    val bookState = _bookState.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    /*
     * Reactive save states (Expose metadata saving and success state flows)
     * Provides UI observables to represent background write tasks without imperative callbacks.
     */
    private val _isSaving = MutableStateFlow(false)

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess = _saveSuccess.asStateFlow()

    /**
     * Start Edit Flow (Initialize edit target and overlay visibility)
     *
     * Loads the requested book before showing the route so the screen can render either a loading state
     * or the resolved metadata without owning repository access.
     */
    fun startEdit(bookId: String) {
        /*
         * Reset success status (Purge state of prior successful save flows)
         * Guarantees that LaunchedEffect triggers only on new save results.
         */
        _saveSuccess.value = false
        loadBook(bookId)
        setVisible(true)
    }

    /**
     * Set Overlay Visibility (Visibility control and state reset)
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
     * Load Book Details (Asynchronous edit read-model query)
     *
     * Reads the editable book entity through the edit scene module so route code stays off broad library dependencies.
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            _bookState.value = editBookReadModel.getEditableBook(bookId)
        }
    }

    /**
     * Save Book Details (Metadata persistence and cover self-healing)
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
            /*
             * Update saving states (Indicate active write operation on coroutine start)
             * Flushes former success flags and turns on the busy state for the edit overlay.
             */
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
                    // Save custom cover using the updated URI pipeline
                    editBookCommands.saveCustomCover(currentBook.id, newCoverUri)
                }
                /*
                 * Mark save success (Trigger reactive dismissals via state flow)
                 * Signifies successful persistence of both metadata properties and custom cover images.
                 */
                _saveSuccess.value = true
            } catch (error: IllegalArgumentException) {
                // Edit Title Rejection Containment (Keep validation failure inside the edit flow)
                // The application edit policy rejects blank titles, so the route stays open and avoids saving cover changes after a failed metadata write.
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
