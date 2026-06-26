package com.viel.oto.ui.common

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.theme.LocalHazeState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * A common glassmorphic overlay dialog rewritten using Haze.
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
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    dialogMaxWidth: Dp = 460.dp,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (dismissOnClickOutside) {
                        onDismissRequest()
                    }
                }
                .padding(horizontal = 24.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            val resolvedHazeState = hazeState ?: LocalHazeState.current
            val dialogShape = MaterialTheme.shapes.extraLarge
            val glassModifier = if (glassEffectMode == GlassEffectMode.Haze && resolvedHazeState != null) {
                Modifier
                    .clip(dialogShape)
                    .hazeEffect(state = resolvedHazeState, style = HazeMaterials.ultraThin())
            } else {
                Modifier
            }

            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = dialogMaxWidth)
                    .then(glassModifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = if (glassEffectMode == GlassEffectMode.Haze) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = if (glassEffectMode == GlassEffectMode.Haze) 0.dp else 6.dp,
                shadowElevation = if (glassEffectMode == GlassEffectMode.Haze) 0.dp else 8.dp
            ) {
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
