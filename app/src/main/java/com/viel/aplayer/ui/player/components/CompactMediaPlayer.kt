
package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import com.viel.aplayer.ui.common.AudioProgressBar
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import com.viel.aplayer.data.store.GlassEffectMode

@OptIn(ExperimentalFoundationApi::class)
@Composable
// 中文注释：已取消封面取色着色功能，移除了 color 参数，迷你播放器进度条直接采用系统默认的 Material 3 主色调
// 详尽的中文注释：新增 backdrop 和 glassEffectMode 两个参数，用来在启用毛玻璃效果时折射底部 NavHost 的画面内容，保持跟搜索/详情页的设计一致。
fun CompactMediaPlayer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Audiobook Title",
    author: String = "Unknown",
    narrator: String = "",
    coverPath: String? = null,
    // 详尽的中文注释：新增封面图像最后修改/重建时间戳，用以打破 Coil 的缓存记录
    coverLastUpdated: Long = 0L,
    progress: () -> Float = { 0f },
    showProgressBar: Boolean = true,
    isMediaAvailable: Boolean = true,
    actions: MiniPlayerActions = MiniPlayerActions(),
    // 为每一次改动添加详尽的中文注释：将共享的模糊状态变更为 miuix-blur 的 LayerBackdrop 采样源参数
    backdrop: LayerBackdrop? = null,
    // 为每一次改动添加详尽的中文注释：新增 onClick 参数，用于接管迷你播放器的全屏展开点击事件，在其 Surface 最外层处理以获取优良的水波纹点击波澜
    onClick: () -> Unit = {},
    // 详尽的中文注释：新增 glassEffectMode 参数，以区分是毛玻璃高斯模糊还是标准 Material 纯色背景
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            // Compact player owns reload-time availability handling and exits when the restored file is gone.
            actions.onUnavailable()
        }
    }

    // 为每一次改动添加详尽的中文注释：对齐新更名的 MiuixBlur，基于 LayerBackdrop 与新枚举判断是否启用毛玻璃渲染
    val isBlurMode = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null

    // 为每一次改动添加详尽的中文注释：获取当前系统的亮暗色主题状态，以实现毛玻璃自适应
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // 为每一次改动添加详尽的中文注释：
    // 将 Surface 改为支持 onClick 的重载，并将传入 of onClick 动作直接挂载在此。
    // 这将实现该紧凑样式播放器卡片本体的完全可点击化，且拥有与 Material 3 一致的水波纹动效表现。
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (isBlurMode) {
                    // 为每一次改动添加详尽的中文注释：使用与 Pill 播放器完全一致的 let 链式动态判定绑定高保真 textureBlur 模糊
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
                // 中文注释：已在此处取消了进度条的封面取色绑定，不再传入自定义 color 属性，使迷你进度条自动回归为 Material 3 主色调
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
                // 详尽的中文注释：将 coverLastUpdated 纳入 remember 的 keys 中。
                // 保证当自愈时间戳变动后，能够引发此处的 File 引用和 UI 重组彻底更新
                val coverFile = remember(coverPath, coverLastUpdated) {
                    coverPath?.let(::File)
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = actions.onHide
                        )
                ) {
                    if (coverFile != null && coverFile.exists()) {
                        // 详尽的中文注释：使用 ImageRequest.Builder 动态构建加载 model，
                        // 并使用具有更新时间戳的 memoryCacheKey 和 diskCacheKey 来打破 Coil 的加载失败及缓存记录，
                        // 迫使 Coil 在物理封面重建后，能够立刻重新读取新的物理文件内容。
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(coverFile)
                                .memoryCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                                .diskCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                                .build(),
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            contentScale = ContentScale.Crop,
                            // 详尽的中文注释：监听 Coil 加载封面图片失败的回调，打印具体的文件绝对路径及异常信息，便于排查 Scoped Storage 或是其它解码错误
                            onError = { errorState ->
                                android.util.Log.e("CompactMediaPlayer", "加载封面图片失败: ${coverFile.absolutePath}, 原因: ", errorState.result.throwable)
                            }
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
