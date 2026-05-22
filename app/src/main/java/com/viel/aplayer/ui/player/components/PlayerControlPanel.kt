package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.PlaybackProgressStateful
import com.viel.aplayer.ui.player.ChapterDisplayStateful
import com.viel.aplayer.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState

/**
 * 详尽中文注释：全屏播放器下半区控制面板。
 * 纵向编排章节标题、进度条、播放控制按钮三个子区域。
 *
 * 内部的进度条 and 章节标题分别采用 Stateful 局部隔间进行包装，只在各自局部订阅对应高/低频数据通道，
 * 从而确保在音乐播放时主播放控制区的重组发生率完美降为 0。
 *
 * @param viewModel 播放器 ViewModel，传入 Stateful 隔间供其内部订阅专属 Flow 通道
 * @param metadata 当前书籍的元数据状态
 * @param controls 播放控制状态（isPlaying / speed）
 * @param settings 播放器 UI 设置状态
 * @param actions 播放器操作回调聚合
 * @param buttonColor 控制按钮的主色调（动画过渡后的封面主色）
 * @param glassEffectMode 播放器当前玻璃视效模式 (Material/Haze)
 * @param hazeState 采样源的 HazeState
 */
@Composable
fun PlayerControlPanel(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    controls: PlayerViewModel.PlaybackControlState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    buttonColor: Color,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        // 详尽中文注释：章节标题显示局部隔间，订阅极其低频的章节变化流，同时在此传入玻璃效果选项与模糊状态采样源
        ChapterDisplayStateful(
            viewModel = viewModel,
            metadata = metadata,
            actions = actions,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
        Spacer(Modifier.height(16.dp))

        // 详尽中文注释：进度条显示局部隔间，只在此内部高频重组
        PlaybackProgressStateful(
            viewModel = viewModel,
            metadata = metadata,
            actions = actions
        )
        Spacer(Modifier.height(24.dp))
        PlaybackControls(
            isPlaying = controls.isPlaying,
            playbackSpeed = controls.playbackSpeed,
            selectedSleepTimer = settings.selectedSleepTimer,
            isSpeedManualMode = controls.isSpeedManualMode,
            actions = actions.playback,
            buttonColor = buttonColor,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
        Spacer(Modifier.height(12.dp))
    }
}
