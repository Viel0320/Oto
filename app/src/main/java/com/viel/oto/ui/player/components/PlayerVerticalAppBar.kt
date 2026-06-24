package com.viel.oto.ui.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.navigation.PlayerNavigationActions
import com.viel.oto.ui.player.BookMetadataState
import com.viel.oto.ui.player.PlayerActions
import com.viel.oto.ui.player.PlayerScreenMode
import com.viel.oto.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun PlayerVerticalAppBar(
    modifier: Modifier = Modifier,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    focusManager: FocusManager,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState? = null,
    offsetY: Animatable<Float, *>,
    scope: CoroutineScope,
    dismissThreshold: Float
) {
    PlayerAppBar(
        title = metadata.title,
        author = metadata.author,
        narrator = metadata.narrator,
        onNavigationClick = {
            focusManager.clearFocus()
            actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
            navigationActions.onMinimize()
        },
        onToggleProgressMode = actions.content.onToggleProgressMode,
        isChapterProgressMode = settings.isChapterProgressMode,
        glassEffectMode = glassEffectMode,
        hazeState = hazeState,
        modifier = modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onVerticalDrag = { change, dragAmount ->
                    val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                    scope.launch {
                        offsetY.snapTo(newOffset)
                    }
                    change.consume()
                },
                onDragEnd = {
                    scope.launch {
                        if (offsetY.value > dismissThreshold) {
                            actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
                            navigationActions.onMinimize()
                        } else {
                            offsetY.animateTo(0f, animationSpec = tween(300))
                        }
                    }
                },
                onDragCancel = {
                    scope.launch { offsetY.animateTo(0f, animationSpec = tween(300)) }
                }
            )
        }
    )
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun PlayerVerticalAppBarPreview() {
    OtoTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PlayerVerticalAppBar(
                metadata = BookMetadataState(title = "The Dark Forest", author = "Cixin Liu", narrator = "Narrator"),
                settings = PlayerSettingsState(),
                actions = PlayerActions(),
                navigationActions = PlayerNavigationActions(),
                focusManager = LocalFocusManager.current,
                glassEffectMode = GlassEffectMode.Material,
                hazeState = null,
                offsetY = remember { Animatable(0f) },
                scope = rememberCoroutineScope(),
                dismissThreshold = 100f
            )
        }
    }
}
