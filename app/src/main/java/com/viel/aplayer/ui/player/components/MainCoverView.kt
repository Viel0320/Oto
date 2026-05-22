package com.viel.aplayer.ui.player.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * 详尽中文注释：全屏播放器的主封面视图。
 * 从 PlayerScreen.kt 提取为独立组件，负责展示有声书封面图片，
 * 并在播放/暂停状态切换时实现轻微缩放动画效果。
 *
 * @param coverPath 封面图的本地物理文件路径
 * @param isPlaying 当前是否正在播放（影响缩放动画）
 * @param coverLastUpdated 封面文件最后更新的时间戳，用于打破 Coil 缓存以实现封面自愈重建后即时刷新
 */
@Composable
fun MainCoverView(
    coverPath: String?,
    isPlaying: Boolean,
    coverLastUpdated: Long = 0L,
    // 详尽中文注释：新增 modifier 参数，允许外部调用者（如 NewPlayerScreen）注入封面手势监听器与自定义样式
    modifier: Modifier = Modifier
) {
    // 详尽中文注释：播放时封面等比缩放至 1.0，暂停时缩至 0.95，配合 300ms 动画营造呼吸感
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = tween(300)
    )

    Box(
        modifier = modifier
            // 为每一次改动添加详尽的中文注释：
            // 按用户最新要求，为了将播放页左栏封面“宽度顶满”且不留边缘横向多余空余，并且在其下方支持 weight(1f) 自适应拉伸占位，
            // 我们在此处将外层 Box 的占满尺寸由 .fillMaxSize() 改为 .fillMaxWidth()，且移除了原先阻碍顶满的 horizontal / vertical padding。
            .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 详尽中文注释：将 coverLastUpdated 纳入 remember 的 keys，确保当数据库自愈更新时间戳改变后，强行使 File 对象及其后的分支重新判断与重绘
        val coverFile = remember(coverPath, coverLastUpdated) {
            if (coverPath != null) File(coverPath) else null
        }
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
        ) {
            if (coverFile != null && coverFile.exists()) {
                // 详尽中文注释：使用 ImageRequest.Builder 重新构建 data model，
                // 并利用具有更新时间戳后缀的 memoryCacheKey 和 diskCacheKey 来打破 Coil 对该图片的加载失败或已有缓存，
                // 确保在封面文件被自愈重建后，Coil 能够丢弃原有失败记忆、即刻从物理文件中拉取并渲染最新的封面。
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(coverFile)
                        .memoryCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                        .diskCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                    onError = { errorState ->
                        android.util.Log.e("MainCoverView", "全屏播放器加载封面图片失败: ${coverFile.absolutePath}, 原因: ", errorState.result.throwable)
                    }
                )
            } else {
                // 详尽中文注释：无封面时展示灰色占位背景 + 播放图标
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(80.dp), tint = Color.Gray)
                }
            }
        }
    }
}
