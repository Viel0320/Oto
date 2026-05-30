package com.viel.aplayer.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.ui.theme.APlayerTheme
import java.io.File
import kotlin.math.abs

/**
 * 自适应手势播放器封面组件。
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
 * @param sizeRatio 封面相对于容器最小维度的尺寸比例，默认为 0.8f (80%)
 * @param gesturesEnabled 是否启用封面上的手势操作（音量调节与切章），默认为 true
 */
@Composable
fun PlayerCover(
    coverPath: String?,
    isPlaying: Boolean,
    coverLastUpdated: Long,
    onAdjustVolume: (Float) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    modifier: Modifier = Modifier,
    sizeRatio: Float = 0.8f,
    gesturesEnabled: Boolean = true
) {
    // 使用 BoxWithConstraints 动态捕获父容器的最大可用宽高，保证在横竖屏或分屏模式下完美自适应
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 比较可用宽度 and 高度，选取较小维度的 sizeRatio 作为封面大小，规避尺寸溢出并保留视觉呼吸感
        val minDimension = minOf(maxWidth, maxHeight)
        val coverSize = minDimension * sizeRatio

        // 用于记录水平拖拽累积量的状态变量，以触发切章手势
        var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
        var hasTriggeredHorizontalDrag by remember { mutableStateOf(false) }

        // 构建手势识别修饰符。仅当 gesturesEnabled 为 true 时才附加 pointerInput 逻辑。
        val gestureModifier = if (gesturesEnabled) {
            Modifier.pointerInput(Unit) {
                // 侦测手势，上下滑动调节音量，左右滑动切换章节
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
                        if (abs(dragAmount.y) > abs(dragAmount.x)) {
                            // 上下滑动时，触发音量调节回调
                            onAdjustVolume(-dragAmount.y * 0.002f)
                        } else if (!hasTriggeredHorizontalDrag) {
                            totalHorizontalDrag += dragAmount.x
                            if (abs(totalHorizontalDrag) > 300f) {
                                // 水平滑动超过阈值（300px）时，触发切章回调
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
        } else {
            Modifier
        }

        MainCoverView(
            coverPath = coverPath,
            isPlaying = isPlaying,
            coverLastUpdated = coverLastUpdated,
            modifier = Modifier
                .size(coverSize)
                .then(gestureModifier)
        )
    }
}

/**
 * 全屏播放器的主封面视图。
 * 从 PlayerScreen.kt 提取为独立组件，负责展示有声书封面图片，
 * 并在播放/暂停状态切换时实现轻微缩放动画效果。
 *
 * @param coverPath 封面图的本地物理文件路径
 * @param isPlaying 当前是否正在播放（影响缩放动画）
 * @param coverLastUpdated 封面文件最后更新的时间戳，用于打破 Coil 缓存以实现封面自愈重建后即时刷新
 */
@Composable
fun MainCoverView(
    modifier: Modifier = Modifier,
    coverPath: String?,
    isPlaying: Boolean,
    coverLastUpdated: Long = 0L
) {
    // 播放时封面等比缩放至 1.0，暂停时缩至 0.95，配合 300ms 动画营造呼吸感
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = tween(300)
    )

    Box(
        modifier = modifier
            //
            // 按用户最新要求，为了将播放页左栏封面“宽度顶满”且不留边缘横向多余空余，并且在其下方支持 weight(1f) 自适应拉伸占位，
            // 我们在此处将外层 Box 的占满尺寸由 .fillMaxSize() 改为 .fillMaxWidth()，且移除了原先阻碍顶满的 horizontal / vertical padding。
            .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    transformOrigin = TransformOrigin(0.5f, 0.0f)
                }
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 定义用于追踪封面异步加载是否失败的局部状态。
            // 结合 Coil 的 onError 回调，可以彻底消除主线程同步调用 File.exists() 的磁盘 I/O 阻塞隐患，
            // 同时也能够处理文件物理存在但格式损坏无法解码的情况，自动平滑切换至占位图。
            var isImageError by remember(coverPath) { mutableStateOf(false) }

            if (coverPath != null && !isImageError) {
                // 使用 ImageRequest.Builder 重新构建 data model，
                // 并利用具有更新时间戳后缀的 memoryCacheKey 和 diskCacheKey 来打破 Coil 对该图片的加载失败或已有缓存，
                // 确保在封面文件被自愈重建后，Coil 能够丢弃原有失败记忆、即刻从物理文件中拉取并渲染最新的封面。
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(File(coverPath))
                        .memoryCacheKey("${coverPath}_$coverLastUpdated")
                        .diskCacheKey("${coverPath}_$coverLastUpdated")
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { errorState ->
                        isImageError = true
                        android.util.Log.e("MainCoverView", "全屏播放器加载封面图片失败: $coverPath, 原因: ", errorState.result.throwable)
                    }
                )
            } else {
                // 当封面路径为空或加载发生异常时，展示统一的占位背景 + 播放图标，视觉对齐详情页规范
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

@Preview(name = "Player Cover - Portrait", showBackground = true, widthDp = 360, heightDp = 640)
@Preview(name = "Player Cover - Landscape", showBackground = true, widthDp = 640, heightDp = 360)
@Composable
fun PlayerCoverPreview() {
    APlayerTheme {
        // 在预览中模拟 PlayerCover。由于预览无法模拟真实手势交互，
        // 这里主要用于验证封面在不同屏幕比例（竖屏 vs 横屏）下的自适应缩放效果。
        PlayerCover(
            coverPath = null, // 传入 null 以展示缺省占位图
            isPlaying = true,
            coverLastUpdated = 0L,
            onAdjustVolume = {},
            onNextChapter = {},
            onPreviousChapter = {},
            sizeRatio = 0.8f
        )
    }
}
