package com.viel.aplayer.ui.common

// Setup Liquid Glass BottomSheet Integration (Route sheet blur through the shared liquid renderer)
// The sheet still consumes HazeState for backdrop sampling, but its Haze mode now draws the same liquid glass refraction and highlight treatment as dialogs and top bars.
// Import Clip Extension (Fix unresolved clip extension reference) Add explicit draw.clip import to allow using Modifier.clip.
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
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import dev.chrisbanes.haze.HazeState

/**
 * BlurModalBottomSheet — Frosted glass BottomSheet refactored using Haze.
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlurModalBottomSheet(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    // Support Nullable HazeState (Provide fallback when hazeState is not ready)
    // Make hazeState optional and default to null so the sheet can degrade gracefully in previews or when parent has no blur context.
    hazeState: HazeState? = null,
    // Glass effect mode must be explicitly passed from the settings state by the caller to prevent BottomSheet from declaring default values privately.
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

    // Set the outer containerColor to transparent in Haze mode, letting the internal frosted glass Box render the background to avoid double backgrounds.
    // Determine Container Color (Use transparent only if Haze blur is active)
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
        // Tonal Elevation Tuning (Dampen elevation to 0.dp only when Haze is active)
        // Set elevation to 0.dp adaptively in Haze mode to completely prevent overlapping gray shadows produced by the system RenderNode on transparent rounded corners.
        tonalElevation = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) 0.dp else tonalElevation,
        scrimColor = scrimColor,
        // Move dragHandle (previously drawn by ModalBottomSheet alone) into the blurred content layer to ensure the handle area shares the same frosted glass background.
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
    ) {
        // Setup Liquid Glass Modifier (Apply the shared liquid glass renderer to modal sheets)
        // Bottom sheets previously used raw HazeMaterials, so chapter panels blurred but did not draw the app's liquid refraction and highlight treatment.
        val glassModifier = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
            Modifier
                // Bottom Sheet Shape Clipping (Constrain blur and border highlights to the sheet outline)
                // The liquid renderer draws shape-aware highlights, so clipping first prevents rectangular blur spill around rounded top corners.
                .clip(shape)
                .liquidGlassCompatEffect(
                    state = hazeState,
                    // Bottom Sheet Liquid Glass Style (Match the Material sheet shape while keeping the shared app tint and blur defaults)
                    // Passing the sheet shape lets the refraction outline follow BottomSheetDefaults.ExpandedShape instead of the generic rounded rectangle default.
                    style = LiquidGlassStyle(shape = shape)
                )
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(glassModifier)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // The original dragHandle slot of Material3 centers by default; it requires restoring fillMaxWidth + Center alignment manually after being moved into the blurred content layer.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // Draw the drag handle inside the same blurred panel to avoid texture fragmentation between the top handle area and the body text.
                    dragHandle?.invoke()
                }

                // Pass through body content provided by the caller; the business layer does not need to perceive the internal blur wrapper.
                content()
            }
        }
    }
}
