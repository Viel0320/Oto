package com.viel.aplayer.ui.common

// Setup Haze Dialog Integration (Replace miuix-blur with dev.chrisbanes.haze) Replaced miuix backdrop APIs with HazeState, hazeChild, and HazeMaterials.
// Import Clip Extension (Fix unresolved clip extension reference) Add explicit draw.clip import to allow using Modifier.clip.
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle

/**
 * BlurDialog (A common glassmorphic overlay dialog rewritten using Haze)
 *
 * Implementation principles:
 * - Callers pass the HazeState of the parent layout into the [hazeState] parameter.
 * - The hazeChild modifier renders a Compose-native glassmorphic overlay.
 *
 * @param onDismissRequest Callback triggered on clicking outside the dialog or pressing the system back button
 * @param hazeState The HazeState linked to the main rendering backdrop
 * @param glassEffectMode Specifies the glass style; skips blur sampling if configured as Material
 * @param scrollable Enables vertical scrolling in the content area if configured as true
 * @param content Composable slot representing the dialog body content
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurDialog(
    onDismissRequest: () -> Unit,
    // Support Nullable HazeState (Provide fallback when hazeState is not ready)
    // Make hazeState optional and default to null so Dialog can degrade gracefully in previews or when parent has no blur context.
    hazeState: HazeState? = null,
    // Parameter Injection Guard (The glass effect mode must be explicitly provided by the caller to avoid declaring implicit defaults)
    glassEffectMode: GlassEffectMode,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // Disable Platform Width Bounds (Bypasses system default dialog widths to enforce widthIn layout constraints)
            usePlatformDefaultWidth = false,
            // Window Edge Extension (Allows dialog contents to layout under system status and navigation bar insets)
            decorFitsSystemWindows = false
        )
    ) {
        // Full-Screen Container (Serves as a layout interceptor configuring outside click touch behavior)
        // Employs a ripple-free clickable modifier to consume clicks on outside regions and trigger [onDismissRequest]
        // when usePlatformDefaultWidth is set to false.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onDismissRequest()
                }
                .padding(horizontal = 24.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            // Setup Glass Modifier (Apply Haze frosted glass effect) Conditionally apply hazeChild modifier if GlassEffectMode.Haze is selected and hazeState is available.
            val glassModifier = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
                val dialogShape = MaterialTheme.shapes.extraLarge
                Modifier
                    // Clip dialog shape before applying hazeChild
                    .clip(dialogShape)
                    // Liquid Glass Dialog Integration (Replace regular haze blur with custom liquid glass effect to add border highlight) Apply liquidGlassCompatEffect to dialog container with the dialog's corner shape.
                    .liquidGlassCompatEffect(
                        state = hazeState,
                        style = LiquidGlassStyle(shape = dialogShape)
                    )
            } else {
                Modifier
            }

            // Content Surface (Core Dialog container styled under Material 3 specification)
            // - Uses extraLarge shapes and tonal/shadow elevation mapping to reinforce spatial layering.
            // - Focus Interceptor: Attaches a custom clickable modifier to prevent gesture events propagating upward and closing the dialog.
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 460.dp)
                    .then(glassModifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // No-Op Gesture Consumption (Empty lambda consuming clicks to prevent triggering dismiss events)
                    },
                shape = MaterialTheme.shapes.extraLarge,
                // Set to transparent in Haze mode to reveal the shader material blur perfectly; Material mode uses an opaque container.
                color = if (glassEffectMode == GlassEffectMode.Haze) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                // Elevation Dampening Guard (Forced to 0.dp under Haze since the Surface backing is transparent)
                // This avoids hardware RenderNode shadows generating unsightly grey border relics.
                tonalElevation = if (glassEffectMode == GlassEffectMode.Haze) 0.dp else 6.dp,
                shadowElevation = if (glassEffectMode == GlassEffectMode.Haze) 0.dp else 8.dp
            ) {
                // Scroll Behavior Toggle (Attaches a vertical scroll modifier if scrollable is set to true)
                val scrollModifier = if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Box(
                    modifier = scrollModifier
                ) {
                    content()
                }
            }
        }
    }
}
