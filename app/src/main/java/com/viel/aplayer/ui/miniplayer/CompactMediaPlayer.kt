package com.viel.aplayer.ui.miniplayer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.AudioProgressBar
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

@OptIn(ExperimentalFoundationApi::class)
@Composable
// 已取消封面取色着色功能，移除了 color 参数，迷你播放器进度条直接采用系统默认的 Material 3 主色调
// 新增 backdrop 和 glassEffectMode 两个参数，用来在启用毛玻璃效果时折射底部 NavHost 的画面内容，保持跟搜索/详情页的设计一致。
fun CompactMediaPlayer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Audiobook Title",
    author: String = "Unknown",
    narrator: String = "",
    coverPath: String? = null,
    // 新增封面图像最后修改/重建时间戳，用以打破 Coil 的缓存记录
    coverLastUpdated: Long = 0L,
    progress: () -> Float = { 0f },
    showProgressBar: Boolean = true,
    isMediaAvailable: Boolean = true,
    actions: MiniPlayerActions = MiniPlayerActions(),
    // 将共享的模糊状态变更为 miuix-blur 的 LayerBackdrop 采样源参数
    backdrop: LayerBackdrop? = null,
    // 新增 onClick 参数，用于接管迷你播放器的全屏展开点击事件，在其 Surface 最外层处理以获取优良的水波纹点击波澜
    onClick: () -> Unit = {},
    // 新增 glassEffectMode 参数，以区分是毛玻璃高斯模糊还是标准 Material 纯色背景
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            // Compact player owns reload-time availability handling and exits when the restored file is gone.
            actions.onUnavailable()
        }
    }

    // 对齐新更名的 MiuixBlur，基于 LayerBackdrop 与新枚举判断是否启用毛玻璃渲染
    val isBlurMode = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null

    // 获取当前系统的亮暗色主题状态，以实现毛玻璃自适应
    val isDark = isSystemInDarkTheme()

    // 
    // 将 Surface 改为支持 onClick 的重载，并将传入 of onClick 动作直接挂载在此。
    // 这将实现该紧凑样式播放器卡片本体的完全可点击化，且拥有与 Material 3 一致的水波纹动效表现。
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (isBlurMode) {
                    // 使用与 Pill 播放器完全一致的 let 链式动态判定绑定高保真 textureBlur 模糊
                    it.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(0.dp),
                        blurRadius = 60f,
                        noiseCoefficient = 0.05f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.76f),
                                    mode = BlurBlendMode.SrcOver
                                )
                            )
                        )
                    )
                } else {
                    it
                }
            },
            color = if (isBlurMode) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(0.dp)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (showProgressBar) {
                // 已在此处取消了进度条的封面取色绑定，不再传入自定义 color 属性，使迷你进度条自动回归为 Material 3 主色调
                AudioProgressBar(
                    progress = progress,
                    onProgressChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    showKnob = false
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = actions.onHide
                        )
                ) {
                    if (coverPath != null) {
                        val context = LocalContext.current
                        // 迷你播放器是常驻小图，固定复用 ThumbnailSmall 规格；
                        // 不同步探测文件存在性，避免播放器常驻区域在重组时触发磁盘 I/O。
                        val request = remember(coverPath, coverLastUpdated) {
                            CoverImageRequestFactory.build(
                                context = context,
                                sourcePath = coverPath,
                                lastUpdated = coverLastUpdated,
                                variant = CoverImageVariant.ThumbnailSmall,
                                scene = "compact-player-cover"
                            )
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = formatPeopleSubtitle(
                            author.takeIf { it.isNotBlank() } ?: "Unknown",
                            narrator.takeIf { it.isNotBlank() } ?: "Unknown"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = actions.onPlayPauseClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            isPlaying = false,
            progress = { 0.23f }
        )
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerPlayingPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            isPlaying = true,
            progress = { 0.65f }
        )
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerNoProgressBarPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            isPlaying = false,
            showProgressBar = false
        )
    }
}

@Preview(showBackground = true, name = "Long Title & Narrator", apiLevel = 36)
@Composable
fun CompactMediaPlayerLongTitlePreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "A Very Long Audiobook Title That Should Marquee Inside The Mini Player",
            author = "Long Author Name",
            narrator = "Narrator One, Narrator Two, Narrator Three",
            isPlaying = true,
            progress = { 0.45f }
        )
    }
}

@Preview(showBackground = true, name = "Author Only", apiLevel = 36)
@Composable
fun CompactMediaPlayerAuthorOnlyPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "Dawn Star",
            author = "Kanae Minato",
            narrator = "",
            isPlaying = false,
            progress = { 0.12f }
        )
    }
}
