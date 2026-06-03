package com.viel.aplayer.ui.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreenMode
import com.viel.aplayer.ui.settings.PlayerSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

// 
// 抽离竖屏播放器顶部栏组件。
// 该组件不仅封装了 PlayerAppBar 的调用，还集成了“下拉最小化”的手势识别逻辑（vertical drag gestures）。
// 通过将手势逻辑与 UI 声明分离，使 PlayerScreen 的主布局结构更加清晰。
@Composable
fun PlayerVerticalAppBar(
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    focusManager: FocusManager,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop,
    offsetY: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    dismissThreshold: Float,
    modifier: Modifier = Modifier
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
        onDeleteBook = actions.content.onDeleteBook,
        isChapterProgressMode = settings.isChapterProgressMode,
        glassEffectMode = glassEffectMode,
        backdrop = backdrop,
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
    APlayerTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PlayerVerticalAppBar(
                metadata = BookMetadataState(title = "三体：黑暗森林", author = "刘慈欣", narrator = "王明"),
                settings = PlayerSettingsState(),
                actions = PlayerActions(),
                navigationActions = PlayerNavigationActions(),
                focusManager = LocalFocusManager.current,
                glassEffectMode = GlassEffectMode.Material,
                backdrop = rememberLayerBackdrop(),
                offsetY = remember { Animatable(0f) },
                scope = rememberCoroutineScope(),
                dismissThreshold = 100f
            )
        }
    }
}
