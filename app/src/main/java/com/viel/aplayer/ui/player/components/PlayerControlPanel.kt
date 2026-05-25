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
import com.viel.aplayer.ui.settings.PlayerSettingsState
import top.yukonga.miuix.kmp.blur.LayerBackdrop

import androidx.compose.foundation.layout.fillMaxWidth

/**
 * 全屏播放器下半区控制面板。
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
 * @param glassEffectMode 播放器当前玻璃视效模式 (Material/miuix-blur)
 * @param backdrop 采样源的 LayerBackdrop
 * @param modifier 外部传入的布局修饰符，便于弹性控制宽度及对齐排版
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
    backdrop: LayerBackdrop?,
    // 新增 modifier 参数以支持外部传入自定义布局修饰符
    modifier: Modifier = Modifier
) {
    Column(
        // 剥离硬编码的 padding(horizontal = 24.dp)，将布局内边距的控制权移交给外部调用者，以实现彻底顶满屏幕或者按需缩进
        // 为整个播放控制面板容器添加 4dp 的内边距，以避免控制面板的子元素紧贴边缘，提升视觉美观度与触控体验
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // 章节标题显示局部隔间，传入 fillMaxWidth 确保能在行中占据全宽，并且在左右添加合适内距
        ChapterDisplayStateful(
            viewModel = viewModel,
            metadata = metadata,
            actions = actions,
            glassEffectMode = glassEffectMode,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        // 进度条显示局部隔间，传入 fillMaxWidth 确保进度条横轴完美铺满，并传入 glassEffectMode 支持液态玻璃折射
        PlaybackProgressStateful(
            viewModel = viewModel,
            metadata = metadata,
            actions = actions,
            modifier = Modifier.fillMaxWidth(),
            glassEffectMode = glassEffectMode
        )
        Spacer(Modifier.height(24.dp))
        
        // 播放控制组件，传入 fillMaxWidth 让底部的五个控制按钮横向等距均匀排开，自适应各尺寸容器宽度
        PlaybackControls(
            isPlaying = controls.isPlaying,
            playbackSpeed = controls.playbackSpeed,
            selectedSleepTimer = settings.selectedSleepTimer,
            isSpeedManualMode = controls.isSpeedManualMode,
            actions = actions.playback,
            buttonColor = buttonColor,
            glassEffectMode = glassEffectMode,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
    }
}
