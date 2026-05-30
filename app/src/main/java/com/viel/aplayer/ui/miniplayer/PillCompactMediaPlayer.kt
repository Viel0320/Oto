package com.viel.aplayer.ui.miniplayer

// 引入 widthIn 修饰符，用于限制悬浮药丸播放器在宽屏/大屏下的最大宽度
// 引入 wrapContentWidth 修饰符，用于支持药丸自适应宽度布局
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import java.io.File

/**
 * 药丸悬浮样式迷你播放器组件 (PillCompactMediaPlayer)
 * 
 * 
 * 1. 采用 Stadium（Pill-shaped）药丸卡片轮廓，悬浮在应用最底部。
 * 2. 完美适配 miuix-blur 高斯模糊，前置调用 clip 切齐圆角以防止硬角溢出，并在模糊模式下混合半透明深灰紫色以提升文字可读性。
 * 3. Canvas 纯手绘圆角进度条，最右端绘制了极小精致的灰白色平衡圆点。
 * 4. 封面大圆角 12.dp。右端控制按钮无背景扁平化。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillCompactMediaPlayer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    coverPath: String? = null,
    // 新增封面图像最后修改/重建时间戳，用以打破 Coil 的缓存记录
    coverLastUpdated: Long = 0L,
    isMediaAvailable: Boolean = true,
    actions: MiniPlayerActions = MiniPlayerActions(),
    // 将共享的模糊状态变更为 miuix-blur 的 LayerBackdrop 采样源参数
    backdrop: LayerBackdrop? = null,
    // 新增 onClick 参数，用于在卡片本体最外层 Surface 上自主处理点击事件，实现完美的胶囊形水波纹效果并完美防误触
    onClick: () -> Unit = {},
    // 新增 glassEffectMode 参数，以区分是毛玻璃高斯模糊还是标准 Material 纯色背景
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    LaunchedEffect(isMediaAvailable) {
        if (!isMediaAvailable) {
            // 在检测到音频不可用时安全退出
            actions.onUnavailable()
        }
    }

    // 基于新更名且无旧模糊机制残余的 LayerBackdrop 与 MiuixBlur 参数判断是否启用毛玻璃渲染
    val isBlurMode = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null
    val pillShape = RoundedCornerShape(100.dp)

    // 使用 Animatable 替代原本的 InfiniteTransition 方案。
    // Animatable 能够被协程精准控制停止，这样在暂停播放时，封面就能停留在当前的物理旋转角度，而不会重置或突变。
    val rotation = remember { Animatable(0f) }

    // 
    // 监听播放状态变动。开启播放时，通过死循环让 Animatable 持续累加角度。
    // 由于 LaunchedEffect 在 isPlaying 变为 false 时会自动取消协程，动画将自然地“原位暂停”。
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                // 在每次执行 360 度循环动画之前，使用 snapTo 将当前角度值重置回 [0, 360) 区间。
                // 这能有效防止超长播放会话时浮点数数值无限累加造成的精度退化与视觉微小抖动，确保动画持续流畅。
                rotation.snapTo(rotation.value % 360f)
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 15000, easing = LinearEasing)
                )
            }
        }
    }

    // 直接读取动画当前的实时数值应用到 UI
    val currentRotation = rotation.value

    // 获取当前系统的亮暗色主题状态，以实现新播放器的全套配色主题切换
    val isDark = isSystemInDarkTheme()

    // 
    // 使用支持 onClick 的 Surface 原生重载，并将其绑定 to onClick。
    // 这可以让 Compose 自动把点击响应热区死死约束在 Surface 定义的形状 (shape = pillShape) 内部。
    // 如此一来，外边的 margin 空白区域 (padding) 以及底部底栏避让区域 (navigationBarsPadding) 不会响应点击，
    // 且水波纹效果呈精致无比、圆润高贵的 Stadium 药丸胶囊状流动，完美复刻高保真设计质感。
    Surface(
        onClick = onClick,
        modifier = modifier
            // 
            // 1. 使用 .fillMaxWidth() 确保组件在底层占满宽度。
            // 2. 核心修改：使用 .wrapContentWidth(Alignment.End) 实现药丸卡片宽度自适应内容，并将其整体靠右对齐。
            .fillMaxWidth()
            .wrapContentWidth(Alignment.End)
            .widthIn(max = 400.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp) // 增加外边距，使卡片精致漂浮在页面之上
            .navigationBarsPadding() // 安全地避开系统底栏区域
            .let {
                if (isBlurMode) {
                    // 
                    // 1. 使用 textureBlur 渲染基础厚高斯模糊（半径 60f），并添加 0.05f 细腻微砂质感。
                    // 2. 将 blendColors 的不透明度（暗色 0.35f，亮色 0.65f）作为背景主基调，确保亮暗环境下出色的底色透射。
                    it.textureBlur(
                        backdrop = backdrop,
                        shape = pillShape,
                        blurRadius = 60f,
                        noiseCoefficient = 0.05f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f),
                                    mode = BlurBlendMode.SrcOver
                                )
                            )
                        )
                    )
                    // 
                    // 3. 链式追加“斜切反射高光层 (Specular Glare)”，通过白色到透明的超轻透感渐变覆盖，
                    //    模拟高光在玻璃凸起面上的物理扫掠折射，极具 3D 浮雕剔透质感。
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.White.copy(alpha = 0.03f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.06f)
                            )
                        ),
                        shape = pillShape
                    )
                    // 
                    // 4. 链式追加“超细微光折射描边 (Refraction Edge)”，使用高对比度渐变细线（1.dp）勾勒边缘。
                    //    这模拟了光线在液态水滴边缘的全反射折射边，让药丸从底层背景脱颖而出，立体高贵。
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(
                                    Color.White.copy(alpha = 0.18f),
                                    Color.White.copy(alpha = 0.02f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.08f)
                                )
                            } else {
                                listOf(
                                    Color.White.copy(alpha = 0.45f),
                                    Color.White.copy(alpha = 0.10f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.25f)
                                )
                            }
                        ),
                        shape = pillShape
                    )
                } else {
                    it
                }
            },
        // 
        // 对齐 CompactMediaPlayer 的背景色设置。
        // 在开启磨砂玻璃 miuix-blur 模式时，将 Surface 背景色设为透明以杜绝色彩叠加遮挡底层模糊渲染；
        // 普通状态下，亮暗主题均统一使用系统 MaterialTheme.colorScheme.surfaceVariant 背景色，保证色彩一致性。
        color = if (isBlurMode) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        // 根据用户要求去掉药丸卡片边缘的所有可能描边。
        // 1. 显式指定 border = null，保证绝对无边界物理描边。
        // 2. 将 shadowElevation（投影高度）调至 0.dp，彻底消除由于高程阴影在深色或亮色背景下所产生的一圈淡灰色“轮廓光晕”视觉描边误差。
        shape = pillShape,
        border = null,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                // 移除 .fillMaxWidth()，使药丸容器宽度根据内部内容自动收缩
                .padding(horizontal = 12.dp) // 保持原有的水平内边距
        ) {
            Row(
                modifier = Modifier
                    // 移除 .fillMaxWidth()，完成“适应内容宽度”的布局改造
                    .padding(vertical = 12.dp) // 通过增加上下内边距撑开高度（约72dp），确保封面在视觉上完美垂直居中
                    .align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 将 coverLastUpdated 纳入 remember 的 keys 中。
                // 保证当自愈时间戳变动后，能够引发此处的 File 引用和 UI 重组彻底更新
                val coverFile = remember(coverPath, coverLastUpdated) {
                    coverPath?.let(::File)
                }

                // 封面： — 12.dp 圆角，与卡片整体的圆润风格相得益彰
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        // 应用旋转动画效果，通过 graphicsLayer 调整 rotationZ，模拟黑胶唱片旋转感
                        .graphicsLayer { rotationZ = currentRotation }
                        .clip(RoundedCornerShape(100.dp))
                        // 
                        // 在开启 miuix-blur 模糊模式时，为黑胶圆盘外圈追加一圈极其高贵、清透剔透的 1.dp 渐变微光折射描边，
                        // 使封面像嵌入水晶防护片盖中一样充满精致的物理反光质感。
                        .let {
                            if (isBlurMode) {
                                it.border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        colors = if (isDark) {
                                            listOf(
                                                Color.White.copy(alpha = 0.18f),
                                                Color.White.copy(alpha = 0.02f),
                                                Color.Transparent,
                                                Color.White.copy(alpha = 0.08f)
                                            )
                                        } else {
                                            listOf(
                                                Color.White.copy(alpha = 0.45f),
                                                Color.White.copy(alpha = 0.10f),
                                                Color.Transparent,
                                                Color.White.copy(alpha = 0.25f)
                                            )
                                        }
                                    ),
                                    shape = RoundedCornerShape(100.dp)
                                )
                            } else {
                                it
                            }
                        }
                        // 
                        // 将胶囊药丸播放器封面的点击事件从空实现修改为传入的 onClick 回调，
                        // 使得点击药丸黑胶唱片封面同样能够拉起全屏播放器页面，
                        // 且长按封面依然支持 actions.onHide 快捷隐藏。
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = actions.onHide
                        )
                ) {
                    if (coverFile != null && coverFile.exists()) {
                        // 使用 ImageRequest.Builder 动态构建加载 model，
                        // 并使用具有更新时间戳的 memoryCacheKey 和 diskCacheKey 来打破 Coil 的加载失败及缓存记录，
                        // 迫使 Coil 在物理封面重建后，能够立刻重新读取新的物理文件内容。
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(coverFile)
                                .memoryCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                                .diskCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                                .build(),
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            contentScale = ContentScale.Crop,
                            // 监听 Coil 加载封面图片失败的回调，打印具体的文件绝对路径及异常信息，便于排查 Scoped Storage 或是其它解码错误
                            onError = { errorState ->
                                Log.e("PillCompactMediaPlayer", "加载封面图片失败: ${coverFile.absolutePath}, 原因: ", errorState.result.throwable)
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

                // 播放/暂停控制按钮： — 保留原本唯一的播放控制功能，无圆边无框极简扁平呈现
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
                        // 对齐 CompactMediaPlayer。
                        // 图标前景色使用自适应的 MaterialTheme.colorScheme.onSurface，以完美适配亮暗主题。
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
