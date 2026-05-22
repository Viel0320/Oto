@file:OptIn(dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class)
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
// 为每一次改动添加详尽的中文注释：引入 widthIn 修饰符，用于限制悬浮药丸播放器在宽屏/大屏下的最大宽度
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
// 为每一次改动添加详尽的中文注释：引入全局的 HazePresets 类以获取高度呼吸感的白羽雾化毛玻璃材质预设
import com.viel.aplayer.ui.common.HazePresets
import dev.chrisbanes.haze.materials.HazeMaterials
import com.viel.aplayer.data.store.GlassEffectMode

/**
 * 药丸悬浮样式迷你播放器组件 (PillCompactMediaPlayer)
 * 
 * 详尽的中文注释：
 * 1. 采用 Stadium（Pill-shaped）药丸卡片轮廓，悬浮在应用最底部。
 * 2. 完美适配 Haze 高斯模糊，前置调用 clip 切齐圆角以防止硬角溢出，并在 Haze 模式下混合半透明深灰紫色以提升文字可读性。
 * 3. Canvas 纯手绘圆角进度条，最右端绘制了极小精致的灰白色平衡圆点。
 * 4. 封面大圆角 12.dp。右端控制按钮无背景扁平化。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillCompactMediaPlayer(
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
    // 详尽的中文注释：新增 hazeState 参数，供模糊玻璃背景采样
    hazeState: HazeState? = null,
    // 为每一次改动添加详尽的中文注释：新增 onClick 参数，用于在卡片本体最外层 Surface 上自主处理点击事件，实现完美的胶囊形水波纹效果并完美防误触
    onClick: () -> Unit = {},
    // 详尽的中文注释：新增 glassEffectMode 参数，以区分是毛玻璃高斯模糊还是标准 Material 纯色背景
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            // 详尽的中文注释：在检测到音频不可用时安全退出
            actions.onUnavailable()
        }
    }

    // 详尽的中文注释：根据传入 of glassEffectMode 和 hazeState 判断当前是否要启用磨砂玻璃高斯模糊背景效果。
    val isHazeMode = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    val pillShape = RoundedCornerShape(100.dp)

    // 为每一次改动添加详尽的中文注释：获取当前系统的亮暗色主题状态，以实现新播放器的全套配色主题切换
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // 为每一次改动添加详尽的中文注释：在 Composable 主作用域中安全提取带有 @Composable 标记的全局 HazeStyle 预设，避免在 remember 闭包内部非法调用
    val baseStyle = HazePresets.HazeStyle

    // 为每一次改动添加详尽的中文注释：
    // 创建药丸播放器专属的高清磨砂玻璃滤镜样式 (pillHazeStyle)。
    // 采用 .copy 深度定制：设置 backgroundColor = Color.Transparent (彻底杜绝不透明底)，
    // 并且根据系统深色/浅色主题自适应调整蒙版色 Tint（暗色下使用高保真半透明深灰紫 Color(0x991D1B22)，亮色下使用清透温润的 80% 亮白 Color.White.copy(alpha = 0.8f)），
    // 使得 Haze 模糊能够完美、无阻挡地在底层折射渲染，呈现极致高贵的自适应磨砂玻璃效果。
    // 为每一次改动添加详尽的中文注释：对齐 CompactMediaPlayer 的 Haze 样式设定，
    // 在深色主题下使用 40% 不透明度的纯黑 (Color.Black.copy(alpha = 0.4f))，使得磨砂高斯模糊效果在不同播放器组件间保持高度一致的通透感。
    val pillHazeStyle = remember(isDark, baseStyle) {
        baseStyle.copy(
            backgroundColor = Color.Transparent,
            tints = listOf(
                dev.chrisbanes.haze.HazeTint(
                    if (isDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.8f)
                )
            )
        )
    }

    // 为每一次改动添加详尽的中文注释：
    // 使用支持 onClick 的 Surface 原生重载，并将其绑定 to onClick。
    // 这可以让 Compose 自动把点击响应热区死死约束在 Surface 定义的形状 (shape = pillShape) 内部。
    // 如此一来，外边的 margin 空白区域 (padding) 以及底部底栏避让区域 (navigationBarsPadding) 不会响应点击，
    // 且水波纹效果呈精致无比、圆润高贵的 Stadium 药丸胶囊状流动，完美复刻高保真设计质感。
    Surface(
        onClick = onClick,
        modifier = modifier
            // 为每一次改动添加详尽的中文注释：限制药丸迷你播放器的最大宽度为 400dp，防止在平板/折叠屏横大屏下过宽导致视觉失调，并自适应居中对齐
            .widthIn(max = 400.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp) // 为每一次改动添加详尽的中文注释：增加外边距，使卡片精致漂浮在页面之上
            .navigationBarsPadding() // 为每一次改动添加详尽的中文注释：安全地避开系统底栏区域
            .let {
                if (isHazeMode) {
                    // 为每一次改动添加详尽的中文注释：关键适配！在高斯模糊前必须执行 clip(pillShape) 裁切，斩断模糊硬角溢出
                    // 为每一次改动添加详尽的中文注释：将磨砂滤镜样式指定为自定义的 pillHazeStyle，以达成温润通透的暗色磨砂玻璃背景。
                    it.clip(pillShape)
                        .hazeEffect(state = hazeState, style = pillHazeStyle)
                } else {
                    it
                }
            },
        // 为每一次改动添加详尽的中文注释：
        // 为每一次改动添加详尽的中文注释：对齐 CompactMediaPlayer 的背景色设置。
        // 在开启磨砂玻璃 Haze 模式时，将 Surface 背景色设为透明以杜绝色彩叠加遮挡底层模糊渲染；
        // 普通状态下，亮暗主题均统一使用系统 MaterialTheme.colorScheme.surfaceVariant 背景色，保证色彩一致性。
        color = if (isHazeMode) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        // 为每一次改动添加详尽的中文注释：根据用户要求去掉药丸卡片边缘的所有可能描边。
        // 1. 显式指定 border = null，保证绝对无边界物理描边。
        // 2. 将 shadowElevation（投影高度）调至 0.dp，彻底消除由于高程阴影在深色或亮色背景下所产生的一圈淡灰色“轮廓光晕”视觉描边误差。
        shape = pillShape,
        border = null,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp) // 详尽的中文注释：保持原有的水平内边距
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp) // 详尽的中文注释：通过增加上下内边距撑开高度（约72dp），确保封面在视觉上完美垂直居中
                    .align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 详尽的中文注释：将 coverLastUpdated 纳入 remember 的 keys 中。
                // 保证当自愈时间戳变动后，能够引发此处的 File 引用和 UI 重组彻底更新
                val coverFile = remember(coverPath, coverLastUpdated) {
                    coverPath?.let(::File)
                }

                // 封面：为每一次改动添加详尽的中文注释 — 12.dp 圆角，与卡片整体的圆润风格相得益彰
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                                android.util.Log.e("PillCompactMediaPlayer", "加载封面图片失败: ${coverFile.absolutePath}, 原因: ", errorState.result.throwable)
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

                // 文本部分：为每一次改动添加详尽的中文注释 — 采用亮白色和淡灰紫，匹配设计图高大上暗色调配色
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        // 为每一次改动添加详尽的中文注释：对齐 CompactMediaPlayer 的配色。
                        // 移除之前硬编码的灰白色 (0xFFE6E1E9)，使用 MaterialTheme.colorScheme.onSurface 自适应前景色，
                        // 彻底解决亮色主题下“白底白字”无法看清的严重体验缺陷。
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatPeopleSubtitle(
                            author.takeIf { it.isNotBlank() } ?: "Unknown",
                            narrator.takeIf { it.isNotBlank() } ?: "Unknown"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        // 为每一次改动添加详尽的中文注释：对齐 CompactMediaPlayer 的副标题配色。
                        // 移除硬编码的灰紫色，改为使用 MaterialTheme.colorScheme.onSurfaceVariant 实现亮暗主题的动态自适应。
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 播放/暂停控制按钮：为每一次改动添加详尽的中文注释 — 保留原本唯一的播放控制功能，无圆边无框极简扁平呈现
                IconButton(
                    onClick = actions.onPlayPauseClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(28.dp),
                        // 为每一次改动添加详尽的中文注释：对齐 CompactMediaPlayer。
                        // 图标前景色使用自适应的 MaterialTheme.colorScheme.onSurface，以完美适配亮暗主题。
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (showProgressBar) {
                // 用 Canvas 手绘极致精致的底端进度条：为每一次改动添加详尽的中文注释 — 复刻圆角与右端小圆点完美细节，并对轨道与激活条进行亮暗自适应处理
                // 详尽的中文注释：使用 align(Alignment.BottomCenter) 将进度条强制置于胶囊最底端，实现“往下移”效果，不占用封面居中的对齐空间
                val primaryColor = MaterialTheme.colorScheme.primary
                // 为每一次改动添加详尽的中文注释：对齐 CompactMediaPlayer 与 AudioProgressBar 组件的进度条轨道底色设计。
                // 不再根据深浅色硬编码特定色彩，而是统一使用主题主色 primary 的 20% 不透明度作为背景底轨，实现视觉风格的完全一致。
                val trackColor = primaryColor.copy(alpha = 0.2f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(horizontal = 2.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    val width = size.width
                    val centerY = size.height / 2
                    val currentProgress = progress()
                    val activeWidth = width * currentProgress

                    // 1. 绘制圆头未激活底轨，提供现代底色厚度感，完美适配系统亮暗色
                    drawLine(
                        color = trackColor,
                        start = Offset(0f, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // 2. 绘制圆头激活轨，高饱和度点亮进度，自适应提取系统主题主色
                    if (activeWidth > 0) {
                        drawLine(
                            color = primaryColor,
                            start = Offset(0f, centerY),
                            end = Offset(activeWidth, centerY),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Pill Style - Paused", apiLevel = 36)
@Composable
fun PillCompactMediaPlayerPreview() {
    APlayerTheme {
        // 详尽的中文注释：全新药丸悬浮样式（暂停状态）的 Compose 预览函数
        PillCompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            isPlaying = false,
            progress = { 0.23f }
        )
    }
}

@Preview(showBackground = true, name = "Pill Style - Playing", apiLevel = 36)
@Composable
fun PillCompactMediaPlayerPlayingPreview() {
    APlayerTheme {
        // 详尽的中文注释：全新药丸悬浮样式（播放状态）的 Compose 预览函数
        PillCompactMediaPlayer(
            title = "In the Megachurch",
            author = "Ryo Asai",
            isPlaying = true,
            progress = { 0.65f }
        )
    }
}

@Preview(showBackground = true, name = "Pill Style - Haze Effect", apiLevel = 36)
@Composable
fun PillCompactMediaPlayerHazePreview() {
    APlayerTheme {
        // 详尽的中文注释：为了展示高斯模糊效果，我们需要创建一个 HazeState 并将其关联到背景容器上
        val hazeState = remember { HazeState() }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                // 详尽的中文注释：使用鲜艳的线性渐变背景，以便肉眼能清晰分辨出毛玻璃的模糊与颜色渗透效果
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFBB86FC),
                            Color(0xFF6200EE),
                            Color(0xFF03DAC6)
                        )
                    )
                )
                // 详尽的中文注释：关键步骤！在背景容器上应用 .hazeSource(state = hazeState) 进行内容采样
                .hazeSource(state = hazeState)
        ) {
            PillCompactMediaPlayer(
                modifier = Modifier.align(Alignment.BottomCenter),
                title = "Glassmorphism Design",
                author = "Haze Effect",
                isPlaying = true,
                progress = { 0.45f },
                // 详尽的中文注释：将采样状态传递给播放器组件，并开启 Haze 模式
                hazeState = hazeState,
                glassEffectMode = GlassEffectMode.Haze
            )
        }
    }
}
