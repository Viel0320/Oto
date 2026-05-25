package com.viel.aplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
// 使用 miuix-blur 的 Backdrop 机制 API 彻底替换旧的模糊库依赖，以实现高保真 textureBlur 噪点磨砂着色高密度模糊
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * BlurSnackbar —— 支持 Material 3 原生样式与 miuix-blur 毛玻璃模糊双态实时切换的通用 Snackbar 包装。
 *
 * 核心原理：
 * 1. 显式圆角裁剪（Modifier.clip）：
 *    在 miuix-blur 模式下，我们将 clip 修饰符放置在 `textureBlur` 之前。这能实现完美圆角边缘采样裁剪。
 * 2. 彻底斩断阴影渗漏（自定义无阴影 Surface）：
 *    我们在 miuix-blur 模式下采用自定义的无阴影 Surface（shadowElevation = 0.dp, tonalElevation = 0.dp），
 *    并通过 defaultMinSize(minHeight) 精确模拟官方 Snackbar 的最小高度，彻底消除阴影黑边渗漏。
 * 3. 颜色自适应与模糊：
 *    - miuix-blur 模式：将 Surface 容器颜色设为完全透明，依靠 textureBlur 渲染模糊背景，并链式涂覆半透明蒙版底色（亮暗自适应）。
 *    - Material 模式：使用标准原生颜色与容器圆角，保证原生风格的极致性能与纯正体验。
 */
@Composable
fun BlurSnackbar(
    backdrop: LayerBackdrop,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
    dismissAction: @Composable (() -> Unit)? = null,
    actionOnNewLine: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    actionContentColor: Color = MaterialTheme.colorScheme.primary,
    dismissActionContentColor: Color = SnackbarDefaults.dismissActionContentColor,
    content: @Composable () -> Unit
) {
    // 根据用户要求，限制 Snackbar 的最大宽度为 480dp，以便在大屏/横屏设备上提供更好的视觉排版与可读性
    val constrainedModifier = modifier.widthIn(max = 480.dp)

    // 对齐新更名的 MiuixBlur，如果是该模式则就地使用 textureBlur 渲染毛玻璃效果
    if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        // 获取当前系统的深色模式状态，以便为毛玻璃做双态色彩自适应
        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
        // 使用 textureBlur 替代原本的 drawBackdrop 物理采样以支持 colored thick 磨砂药丸玻璃质感
        val glassModifier = Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = 60f, // thick -> 厚模糊，提供极佳沉浸感
            noiseCoefficient = 0.05f, // texture -> 强磨砂噪点质感
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(
                        color = if (isDark) Color(0xFF2C2C2C).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.82f), // colored -> 自适应色混
                        mode = BlurBlendMode.SrcOver
                    )
                )
            )
        )
        // 
        // 3. 链式覆盖高光斜向白色物理扫掠折射层 (Specular Glare)，赋予药丸微缩水滴的剔透立体感。
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.03f),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.06f)
                )
            ),
            shape = shape
        )
        // 
        // 4. 链式添加 1.dp 极致精细的“微光折射渐变描边 (Refraction Edge)”，防止在大面积杂色背景上边缘发生粘连。
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
            shape = shape
        )

        // 自定义无阴影的 Surface，强制阴影与色调高度为 0.dp 以杜绝黑边投影伪像，
        // 并通过挂载 miuix-blur 绘制模糊背景与亮暗自适应半透明底色，实现极其华丽、通透且清晰的高阶毛玻璃效果。
        Surface(
            modifier = constrainedModifier
                .then(glassModifier),
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            // 高精模拟官方 M3 Snackbar 布局，利用 defaultMinSize 强制约束最小高度，支持 actionOnNewLine 特性与 dismissAction
            if (actionOnNewLine) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 68.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        content()
                    }
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (action != null) action()
                        if (dismissAction != null) dismissAction()
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(
                            start = 16.dp,
                            end = if (action != null || dismissAction != null) 8.dp else 16.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // 根据是否有 Action 动态精准分配上下边距（10.dp 或 14.dp），防止 Row 与内部子项 Padding 发生叠加撑开
                            .padding(vertical = if (action != null || dismissAction != null) 10.dp else 14.dp)
                    ) {
                        content()
                    }

                    if (action != null) action()
                    if (dismissAction != null) dismissAction()
                }
            }
        }
    } else {
        // Material 原生模式，直接沿用官方标准 Snackbar，保留最佳底层绘制兼容性
        Snackbar(
            modifier = constrainedModifier,
            action = action,
            dismissAction = dismissAction,
            actionOnNewLine = actionOnNewLine,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            actionContentColor = actionContentColor,
            dismissActionContentColor = dismissActionContentColor,
            content = content
        )
    }
}
