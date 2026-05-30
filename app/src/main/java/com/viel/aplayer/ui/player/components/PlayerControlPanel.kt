package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.settings.PlayerSettingsState
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 全屏播放器下半区控制面板。
 * 纵向编排章节标题、进度条、播放控制按钮三个子区域。
 *
 * 已经过重构彻底去除了对 PlayerViewModel 及其内部 State 类型的任何依赖，
 * 所有状态均通过基础类型与通用实体显式参数传递，实现了 Layer 3 无状态纯渲染组件的极致解耦与极致的重绘隔离。
 *
 * @param currentPosition 播放器当前物理播放进度（毫秒）
 * @param totalDuration 播放器当前物理总时长（毫秒）
 * @param isChapterMode 当前进度条是否处于章节进度视图模式
 * @param currentChapter 当前正处于播放状态的章节实体
 * @param metadata 当前书籍的元数据状态
 * @param isPlaying 当前是否处于播放中状态
 * @param playbackSpeed 当前的播放速率
 * @param isSpeedManualMode 播放速率是否被手动调节锁定
 * @param settings 播放器 UI 设置状态
 * @param actions 播放器操作回调聚合
 * @param buttonColor 控制按钮的主色调（动画过渡后的封面主色）
 * @param glassEffectMode 播放器当前玻璃视效模式 (Material/miuix-blur)
 * @param backdrop 采样源的 LayerBackdrop
 * @param modifier 外部传入的布局修饰符，便于弹性控制宽度及对齐排版
 */
@Composable
fun PlayerControlPanel(
    currentPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    currentChapter: ChapterEntity?,
    metadata: BookMetadataState,
    isPlaying: Boolean,
    playbackSpeed: Float,
    isSpeedManualMode: Boolean,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    buttonColor: Color,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier
) {
    Column(
        // 剥离硬编码的 padding(horizontal = 24.dp)，将布局内边距的控制权移交给外部调用者，以实现彻底顶满屏幕或者按需缩进
        // 为整个播放控制面板容器添加 4dp 的内边距，以避免控制面板的子元素紧贴边缘，提升视觉美观度与触控体验
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // 章节标题显示组件，已解耦 ViewModel，直接调用无状态组件，传入解包后的当前章节标题等参数
        ChapterDisplay(
            currentChapterTitle = currentChapter?.title ?: metadata.title,
            onChapterClick = actions.content.onShowChapterList,
            onBookmarkClick = actions.bookmarks.onShowDialog,
            glassEffectMode = glassEffectMode,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        // 进度条显示组件，已解耦 ViewModel，直接传入当前播放位置、总时长以及对应的章节划分，实现极致渲染性能
        PlaybackProgress(
            currentPosition = currentPosition,
            totalDuration = totalDuration,
            isChapterMode = isChapterMode,
            // 从元数据列表中映射并就地解包出无关联的章节物理定义，以匹配 Stateless 组件的数据类型要求
            chapters = metadata.chapters.map { it.chapter },
            markers = metadata.getChapterMarkers(totalDuration),
            onSeek = { pos -> actions.playback.onSeek(pos, true) },
            modifier = Modifier.fillMaxWidth(),
            glassEffectMode = glassEffectMode
        )
        Spacer(Modifier.height(24.dp))
        
        // 播放控制组件，传入 fillMaxWidth 让底部的五个控制按钮横向等距均匀排开，自适应各尺寸容器宽度
        PlaybackControls(
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            selectedSleepTimer = settings.selectedSleepTimer,
            isSpeedManualMode = isSpeedManualMode,
            actions = actions.playback,
            buttonColor = buttonColor,
            glassEffectMode = glassEffectMode,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
    }
}

