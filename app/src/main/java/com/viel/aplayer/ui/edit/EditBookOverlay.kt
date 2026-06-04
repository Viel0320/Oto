package com.viel.aplayer.ui.edit

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * EditBookViewModel Setup (Lightweight Meta Editing ViewModel)
 *
 * Lightweight normalized ViewModel for editing book metadata.
 * Its lifecycle has been successfully decoupled from EditBookActivity and is now hosted in the Activity-level scope of the main App.
 */
class EditBookViewModel(application: Application) : AndroidViewModel(application) {
    // M6 Refactoring: Completely remove dependency on legacy repositories in EditBookViewModel, replacing it with the high-level business facade libraryFacade.
    private val libraryFacade = (application as APlayerApplication).container.libraryFacade

    private val _bookState = MutableStateFlow<BookEntity?>(null)
    val bookState = _bookState.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    /**
     * Start Edit Flow (Initialize Edit and Visible State)
     *
     * Starts the book editing process. Triggers asynchronous data reading and sets the visibility state of the floating overlay to true.
     */
    fun startEdit(bookId: String) {
        loadBook(bookId)
        setVisible(true)
    }

    /**
     * Set Overlay Visibility (Visibility Control and State Reset)
     *
     * Controls the visibility of the editing overlay.
     * When closing the overlay, actively sets bookState to null to clear dirty data cache and prevent UI flickering on the next launch.
     */
    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
        if (!visible) {
            _bookState.value = null
        }
    }

    /**
     * Load Book Details (Asynchronous Room Query)
     *
     * Asynchronously loads the underlying Room entity record of a single book based on the book ID.
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            // Use libraryFacade high-level facade to asynchronously obtain book details
            _bookState.value = libraryFacade.getBookById(bookId)
        }
    }

    /**
     * Save Book Details (Metadata Persistence and Cover Self-healing)
     *
     * Asynchronously saves the edited metadata and user-uploaded custom cover path back to the database.
     * @param newCoverPath Absolute physical path of the temporary cover file generated after custom cropping; null if cover is unchanged.
     * @param onComplete Callback after save and persistence success, typically used to close the floating overlay.
     */
    fun saveBook(
        title: String,
        author: String,
        narrator: String,
        year: String,
        description: String,
        newCoverPath: String?,
        onComplete: () -> Unit
    ) {
        val currentBook = _bookState.value ?: return
        viewModelScope.launch {
            // Use libraryFacade to asynchronously persist modified text metadata fields into Room
            libraryFacade.updateBookDetails(
                id = currentBook.id,
                title = title.trim(),
                author = author.trim(),
                narrator = narrator.trim(),
                description = description.trim(),
                year = year.trim()
            )
            // If the cover is replaced, call libraryFacade cascade storage to physically clean up legacy cover remnants and refresh self-healing covers
            if (newCoverPath != null) {
                libraryFacade.saveCustomCover(currentBook.id, newCoverPath)
            }
            onComplete()
        }
    }
}

/**
 * EditBookOverlay Setup (Stateful Edit Book Overlay Wrapper)
 *
 * Stateful Composable book editing overlay wrapper component (Stateful Overlay).
 * Responsible for hosting and managing all business lifecycles and state subscription logic,
 * collecting `isVisible` and `bookState` data from ViewModel as needed,
 * and passing data and operations as Lambda callbacks to the underlying stateless rendering component `EditBookScreen` during smooth transition animations.
 *
 * @param editViewModel Associated lightweight book edit ViewModel instance
 * @param glassEffectMode Current glass effect mode config of the system
 * @param modifier External modifier
 * @param backdrop Shared blur sampling source rendered from the details page
 * @param onSaveSuccess Host-level event callback after successful book saving
 */
@Composable
fun EditBookOverlay(
    editViewModel: EditBookViewModel,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Add detailBackdrop parameter, receiving the blur sampling source rendered from the details page
    backdrop: LayerBackdrop? = null,
    onSaveSuccess: () -> Unit = {}
) {
    val isVisible by editViewModel.isVisible.collectAsStateWithLifecycle()
    // Subscribe to the stateful bookState data model here and pass it down, adhering to unidirectional data flow and separation of concerns
    val book by editViewModel.bookState.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        // Call the completely decoupled stateless EditBookScreen UI component
        EditBookScreen(
            book = book,
            onNavigationBack = { editViewModel.setVisible(false) },
            onSave = { title, author, narrator, year, description, newCoverPath ->
                editViewModel.saveBook(
                    title = title,
                    author = author,
                    narrator = narrator,
                    year = year,
                    description = description,
                    newCoverPath = newCoverPath,
                    onComplete = {
                        onSaveSuccess()
                        editViewModel.setVisible(false)
                    }
                )
            },
            glassEffectMode = glassEffectMode,
            detailBackdrop = backdrop
        )
    }
}
