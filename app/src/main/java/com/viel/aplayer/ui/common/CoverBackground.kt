package com.viel.aplayer.ui.detail.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import java.io.File

/**
 * 为每一次改动添加详尽的中文注释：
 * 重构后的详情页背景组件。
 * 1. 集成了原 DetailScreen 中的背景渐变逻辑、动画逻辑及 miuix-blur 采样源挂载逻辑。
 * 2. 只有在 miuix-blur 模式下才会渲染强模糊封面背景，以对齐播放页视觉。
 * 3. 统一管理背景遮罩与渐变，确保 UI 逻辑内聚，简化 DetailScreen 结构。
 */
@Composable
fun CoverBackground(
    book: BookEntity?,
    backgroundColorArgb: Int,
    glassEffectMode: GlassEffectMode,
    detailBackdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    val isDark = isSystemInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background

    // 为每一次改动添加详尽的中文注释：动态监听并平滑过渡背景主色调，确保封面切换时视觉连贯。
    val animatedBgColor by animateColorAsState(
        targetValue = Color(backgroundColorArgb),
        animationSpec = tween(300),
        label = "bg_color"
    )

    // 为每一次改动添加详尽的中文注释：根据是否开启毛玻璃模式，动态计算背景渐变笔刷。
    // 在 miuix-blur 模式下大幅降低透明度，以便透出底层的封面强模糊图。
    val backgroundBrush by remember(animatedBgColor, bgColor, isBlur) {
        derivedStateOf {
            if (isBlur) {
                Brush.verticalGradient(
                    colors = listOf(
                        animatedBgColor.copy(alpha = 0.35f),
                        bgColor.copy(alpha = 0.5f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        animatedBgColor.copy(alpha = 0.9f),
                        bgColor.copy(alpha = 0.95f)
                    )
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .then(
                // 为每一次改动添加详尽的中文注释：在此节点挂载采样源。所有兄弟节点（如菜单、弹窗）
                // 均可基于此层生成的图像进行实时磨砂采样。
                if (isBlur) {
                    Modifier.layerBackdrop(detailBackdrop)
                } else {
                    Modifier
                }
            )
    ) {
        // 为每一次改动添加详尽的中文注释：只有在 miuix-blur 模式且存在有效封面路径时，才渲染全屏封面背景。
        if (isBlur && book?.coverPath != null) {
            val context = LocalContext.current
            val bgRequest = remember(book.coverPath, book.lastScannedAt) {
                ImageRequest.Builder(context)
                    .data(File(book.coverPath))
                    .memoryCacheKey("${book.coverPath}?bg=true&t=${book.lastScannedAt}")
                    .diskCacheKey("${book.coverPath}?bg=true&t=${book.lastScannedAt}")
                    .crossfade(true)
                    .build()
            }

            // 为每一次改动添加详尽的中文注释：使用详情页封面本身铺满背景，并通过 Compose 自身强模糊（64.dp）打造流体氛围背景。
            AsyncImage(
                model = bgRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 微调放大比例防止模糊溢出黑边
                        scaleX = 1.12f
                        scaleY = 1.12f
                    }
                    .blur(64.dp)
            )

            // 为每一次改动添加详尽的中文注释：叠加主题背景自适应遮罩层，提升文字和元数据前景色对比度。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor.copy(alpha = if (isDark) 0.62f else 0.74f))
            )

            // 为每一次改动添加详尽的中文注释：底部微调遮罩渐变层，保持全局排版底部的对比度一致性。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                bgColor.copy(alpha = if (isDark) 0.46f else 0.34f)
                            )
                        )
                    )
            )
        }
    }
}
