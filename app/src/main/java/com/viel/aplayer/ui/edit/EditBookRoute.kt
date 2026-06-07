package com.viel.aplayer.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * Edit Book Route (Stateful edit metadata route adapter)
 *
 * Collects EditBookViewModel state and adapts save callbacks before rendering the stateless
 * EditBookScreen inside the stateless EditBookOverlay animation shell.
 */
@Composable
fun EditBookRoute(
    editViewModel: EditBookViewModel,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Haze Route Input (Uses the stable app-level source for edit-sheet glass controls)
    // Keeping this value at the route boundary avoids coupling EditBookOverlay to backdrop ownership.
    hazeState: HazeState? = null,
    onSaveSuccess: () -> Unit = {}
) {
    val isVisible by editViewModel.isVisible.collectAsStateWithLifecycle()
    val book by editViewModel.bookState.collectAsStateWithLifecycle()

    EditBookOverlay(
        visible = isVisible,
        modifier = modifier
    ) {
        // Edit Screen Stable Haze Parameter (Link edit glass to the app-level sampler)
        // The edit sheet samples whichever screen is registered under the stable app source instead of rebinding to Detail's local source.
        EditBookScreen(
            book = book,
            onNavigationBack = { editViewModel.setVisible(false) },
            onSave = { title, author, narrator, year, description, series, newCoverPath ->
                // Edit Save Adapter (Forward all editable metadata fields through the route)
                // Route-level adaptation keeps EditBookScreen stateless and keeps repository calls inside EditBookViewModel.
                editViewModel.saveBook(
                    title = title,
                    author = author,
                    narrator = narrator,
                    year = year,
                    description = description,
                    series = series,
                    newCoverPath = newCoverPath,
                    onComplete = {
                        onSaveSuccess()
                        editViewModel.setVisible(false)
                    }
                )
            },
            glassEffectMode = glassEffectMode,
            detailHazeState = hazeState
        )
    }
}
