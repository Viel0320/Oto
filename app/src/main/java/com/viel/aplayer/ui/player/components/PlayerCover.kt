package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 详尽中文注释：自适应手势播放器封面组件。
 * 从 PlayerScreen.kt 中将 BoxWithConstraints 及其嵌套的手势监听与尺寸计算逻辑独立出来，
 * 封装为统一且高度重用的 PlayerCover 组件，实现布局层级解耦与性能优化。
 *
 * @param coverPath 封面图的本地物理文件路径
 * @param isPlaying 当前是否正在播放（影响缩放动画）
 * @param coverLastUpdated 封面文件最后更新的时间戳，用于打破 Coil 缓存
 * @param onAdjustVolume 触发音量调节的回调函数
 * @param onNextChapter 触发下一章的回调函数
 * @param onPreviousChapter 触发上一章的回调函数
 * @param modifier 外部修饰符
 */
@Composable
fun PlayerCover(
    coverPath: String?,
    isPlaying: Boolean,
    coverLastUpdated: Long,
    onAdjustVolume: (Float) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 详尽中文注释：使用 BoxWithConstraints 动态捕获父容器的最大可用宽高，保证在横竖屏或分屏模式下完美自适应
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 详尽中文注释：比较可用宽度 and 高度，选取较小维度的 80% 作为封面大小，规避尺寸溢出并保留视觉呼吸感
        val minDimension = minOf(maxWidth, maxHeight)
        val coverSize = minDimension * 0.8f

        // 详尽中文注释：用于记录水平拖拽累积量的状态变量，以触发切章手势
        var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
        var hasTriggeredHorizontalDrag by remember { mutableStateOf(false) }

        MainCoverView(
            coverPath = coverPath,
            isPlaying = isPlaying,
            coverLastUpdated = coverLastUpdated,
            modifier = Modifier
                .size(coverSize)
                .pointerInput(Unit) {
                    // 详尽中文注释：侦测手势，上下滑动调节音量，左右滑动切换章节
                    detectDragGestures(
                        onDragStart = {
                            totalHorizontalDrag = 0f
                            hasTriggeredHorizontalDrag = false
                        },
                        onDragEnd = {
                            totalHorizontalDrag = 0f
                            hasTriggeredHorizontalDrag = false
                        },
                        onDragCancel = {
                            totalHorizontalDrag = 0f
                            hasTriggeredHorizontalDrag = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x)) {
                                // 详尽中文注释：上下滑动时，触发音量调节回调
                                onAdjustVolume(-dragAmount.y * 0.002f)
                            } else if (!hasTriggeredHorizontalDrag) {
                                totalHorizontalDrag += dragAmount.x
                                if (kotlin.math.abs(totalHorizontalDrag) > 300f) {
                                    // 详尽中文注释：水平滑动超过阈值（300px）时，触发切章回调
                                    if (totalHorizontalDrag > 0) {
                                        onNextChapter()
                                    } else {
                                        onPreviousChapter()
                                    }
                                    hasTriggeredHorizontalDrag = true
                                }
                            }
                        }
                    )
                }
        )
    }
}
