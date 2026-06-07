package com.viel.aplayer.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.common.theme.DynamicColorSchemeHelper
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import dev.chrisbanes.haze.HazeState

/**
 * Detail Route (Stateful detail page route adapter)
 *
 * Owns DetailViewModel collection, dynamic cover color state, and user-effect callbacks before handing
 * pure rendering work to DetailOverlay and DetailScreen.
 */
@Composable
fun DetailRoute(
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    onPlayBook: (String) -> Unit,
    onNavigateToSearch: (String) -> Unit,
    // Glass Effect Route Input (Receives app-level visual mode without hard-coded defaults)
    // DetailRoute passes this value through to both the overlay shell and stateless screen.
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Haze Route Inputs (Separate app-level and detail-local sampling sources)
    // Route-level wiring keeps glass source ownership explicit while DetailScreen remains stateless.
    hazeState: HazeState? = null,
    detailHazeState: HazeState? = null,
    onEditClick: (String) -> Unit = {},
) {
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = LocalDarkTheme.current
    val currentColorScheme = MaterialTheme.colorScheme
    val coverPath = detailUiState.book?.book?.coverPath

    // Cover Color Route State (Reset per selected artwork and seed the detail theme from cached extraction)
    // The color is route state because it coordinates theme selection and render callbacks without belonging to the stateless screen.
    var coverColor by remember(coverPath) {
        mutableStateOf<Color?>(ImageProcessor.getCachedColor(coverPath)?.let { Color(it) })
    }

    val detailColorScheme = remember(coverColor, darkTheme, currentColorScheme) {
        coverColor?.let { color ->
            DynamicColorSchemeHelper.generateColorSchemeFromSeed(color, darkTheme, currentColorScheme)
        }
    }

    DetailOverlay(
        visible = detailUiState.isVisible,
        glassEffectMode = glassEffectMode,
        modifier = modifier,
        hazeState = hazeState,
        detailHazeState = detailHazeState
    ) {
        val screenBlock = @Composable {
            DetailScreen(
                uiState = detailUiState,
                onBackClick = { detailViewModel.setVisible(false) },
                onPlayPressed = { detailViewModel.onPlayPressed() },
                onSearchClick = { query ->
                    if (canStartNavigation()) {
                        // In-Place Search Activation (Open search while preserving the selected detail context)
                        // Search routing remains a host-level effect and does not mutate DetailScreen visibility.
                        onNavigateToSearch(query)
                    }
                },
                onPlayClick = {
                    detailUiState.book?.let { bookWithProgress ->
                        // Host Playback Command (Route selected detail item playback to PlayerViewModel in the app shell)
                        // DetailScreen only reports the action; route-level wiring resolves the current book identifier.
                        onPlayBook(bookWithProgress.book.id)
                    }
                },
                glassEffectMode = glassEffectMode,
                hazeState = hazeState,
                fullPageHazeState = hazeState,
                onEditClick = onEditClick,
                coverColor = coverColor,
                onColorExtracted = { coverColor = it }
            )
        }

        // Detail Theme Application (Apply book-seeded color only around the stateless detail screen)
        // Keeping theme selection here prevents DetailScreen from needing ImageProcessor or dynamic theme knowledge.
        if (detailColorScheme != null) {
            MaterialTheme(colorScheme = detailColorScheme, content = screenBlock)
        } else {
            screenBlock()
        }
    }
}
