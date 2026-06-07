package com.viel.aplayer.ui.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookEntity
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
    // Library Presentation Dependency View (Resolve only the facade required by edit flows)
    // EditBookViewModel loads and saves book metadata without receiving settings, playback, or worker dependencies.
    private val libraryFacade = APlayerApplication.getLibraryPresentationDependencies(application).libraryFacade

    private val _bookState = MutableStateFlow<BookEntity?>(null)
    val bookState = _bookState.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    /**
     * Start Edit Flow (Initialize edit target and overlay visibility)
     *
     * Loads the requested book before showing the route so the screen can render either a loading state
     * or the resolved metadata without owning repository access.
     */
    fun startEdit(bookId: String) {
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
     * Load Book Details (Asynchronous facade query)
     *
     * Reads the editable book entity through LibraryFacade so route code stays on the UI-facing application seam.
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            _bookState.value = libraryFacade.getBookById(bookId)
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
        newCoverPath: String?,
        onComplete: () -> Unit
    ) {
        val currentBook = _bookState.value ?: return
        viewModelScope.launch {
            libraryFacade.updateBookDetails(
                id = currentBook.id,
                title = title.trim(),
                author = author.trim(),
                narrator = narrator.trim(),
                description = description.trim(),
                year = year.trim(),
                series = series.trim()
            )
            if (newCoverPath != null) {
                libraryFacade.saveCustomCover(currentBook.id, newCoverPath)
            }
            onComplete()
        }
    }
}
