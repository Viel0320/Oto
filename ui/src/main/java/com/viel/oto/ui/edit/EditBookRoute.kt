package com.viel.oto.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.uiPerformanceTrace
import dev.chrisbanes.haze.HazeState

/**
 * Stateful edit metadata route adapter.
 *
 * Collects EditBookViewModel state and adapts save callbacks before rendering the stateless
 * EditBookScreen inside the stateless EditBookOverlay animation shell.
 */
@Composable
fun EditBookRoute(
    editViewModel: EditBookViewModel,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onSaveSuccess: () -> Unit = {}
) {
    val isVisible by editViewModel.isVisible.collectAsStateWithLifecycle()
    val book by editViewModel.bookState.collectAsStateWithLifecycle()
    val saveSuccess by editViewModel.saveSuccess.collectAsStateWithLifecycle()
    val editTraceState = "visible=$isVisible,hasBook=${book != null},saveSuccess=$saveSuccess"

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            onSaveSuccess()
            editViewModel.setVisible(false)
        }
    }

    EditBookOverlay(
        visible = isVisible,
        modifier = modifier.uiPerformanceTrace(
            node = "EditBookRoute",
            route = "EditBook",
            state = editTraceState
        )
    ) {
        EditBookScreen(
            book = book,
            onNavigationBack = { editViewModel.setVisible(false) },
            onSave = { title, author, narrator, year, description, series, newCoverUri ->
                editViewModel.saveBook(
                    title = title,
                    author = author,
                    narrator = narrator,
                    year = year,
                    description = description,
                    series = series,
                    newCoverUri = newCoverUri
                )
            },
            glassEffectMode = glassEffectMode,
            detailHazeState = hazeState
        )
    }
}
