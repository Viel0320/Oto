package com.viel.aplayer.ui.common
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.shared.settings.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Frosted glass BottomSheet wrapper using Haze when available.
 *
 * Implementation Principles:
 * Material3's [ModalBottomSheet] uses an independent Dialog Window internally to host content. We need
 * to pass the parent layout's [HazeState] here to draw the Compose-native blur.
 *
 * @param onDismissRequest Callback invoked when clicking on the scrim or sliding down to close.
 * @param hazeState The HazeState linked to the main rendering background.
 * @param glassEffectMode Current glass effect mode. Material mode does not attach the blur modifier.
 * @param sheetState BottomSheet state, controlling expanded/collapsed/partially expanded states.
 * @param shape The shape of the BottomSheet panel's top corners, defaulting to BottomSheetDefaults.ExpandedShape from the Material3 specification.
 * @param containerColor Panel background color, defaulting to surfaceContainerLow + 0.78f alpha.
 * @param contentColor Default foreground color of the content.
 * @param tonalElevation Tonal elevation.
 * @param scrimColor Scrim mask color.
 * @param dragHandle Top drag handle Composable, defaulting to the Material3 standard handle.
 * @param contentWindowInsets Window insets for the BottomSheet content area, defaulting to not consuming system bars.
 * @param modifier Modifier.
 * @param content BottomSheet body content (ColumnScope).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurModalBottomSheet(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 8.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit
) {

    val sheetContainerColor = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        Color.Transparent
    } else {
        containerColor
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = sheetContainerColor,
        contentColor = contentColor,
        tonalElevation = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) 0.dp else tonalElevation,
        scrimColor = scrimColor,
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
    ) {
        val glassModifier = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
            Modifier
                .clip(shape)
                .hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(glassModifier)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    dragHandle?.invoke()
                }

                content()
            }
        }
    }
}
