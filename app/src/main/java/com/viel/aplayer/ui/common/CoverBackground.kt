package com.viel.aplayer.ui.common

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
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 全局通用的背景封面强模糊氛围组件，适用于播放页与详情页。
 * 1. 自动处理背景主色的平滑颜色动画。
 * 2. 在 MiuixBlur 模式下挂载 layerBackdrop 采样源，并渲染 64.dp 强模糊封面。
 * 3. 自动适配亮暗色主题遮罩，确保前景 UI 的识别度。
 */
@Composable
fun CoverBackground(
    coverPath: String?,
    lastUpdated: Long,
    backgroundColorArgb: Int,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    val isDark = isSystemInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background

    // 平滑过渡背景主色调，确保切换书籍时视觉无缝衔接。
    val animatedBgColor by animateColorAsState(
        targetValue = Color(backgroundColorArgb),
        animationSpec = tween(300),
        label = "bg_color"
    )

    // 根据是否开启毛玻璃模式计算背景渐变笔刷。
    // 在 MiuixBlur 模式下大幅降低透明度以透出底层模糊图。
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
                // 挂载采样源，为前景组件提供磨砂背景图像源。
                if (isBlur) {
                    Modifier.layerBackdrop(backdrop)
                } else {
                    Modifier
                }
            )
    ) {
        // 只有在 MiuixBlur 模式下才渲染全屏封面模糊背景。
        if (isBlur && coverPath != null) {
            val context = LocalContext.current
            val bgRequest = remember(coverPath, lastUpdated) {
                // 背景图只作为模糊采样源，固定使用 Backdrop 规格并禁用 hardware bitmap；
                // 这样既能减少 Bitmap 体积，也避免软件模糊链路后续读取硬件位图时出现兼容风险。
                CoverImageRequestFactory.build(
                    context = context,
                    sourcePath = coverPath,
                    lastUpdated = lastUpdated,
                    variant = CoverImageVariant.Backdrop,
                    scene = "cover-backdrop",
                    allowHardware = false
                )
            }

            AsyncImage(
                model = bgRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.12f
                        scaleY = 1.12f
                    }
                    .blur(64.dp)
            )

            // 叠加自适应主题遮罩层。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor.copy(alpha = if (isDark) 0.62f else 0.74f))
            )

            // 底部渐变加深层。
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
