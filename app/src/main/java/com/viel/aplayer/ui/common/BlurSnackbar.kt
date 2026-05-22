package com.viel.aplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * 为每一次改动添加详尽的中文注释：
 * BlurSnackbar —— 支持 Material 3 原生样式与 Haze 毛玻璃模糊双态实时切换的通用 Snackbar 包装。
 *
 * 核心原理：
 * 1. 显式圆角裁剪（Modifier.clip）：
 *    在 Haze 模式下，我们将 clip 修饰符放置在 `hazeEffect` 之前。由于 Haze 默认采用矩形进行采样和模糊，
 *    如果不提前进行圆角裁剪，直角像素的模糊效果会溢出并与容器圆角边缘重叠，形成深色的边缘伪像（边框）。
 * 2. 彻底斩断阴影渗漏（自定义无阴影 Surface）：
 *    由于 M3 原生 Snackbar 内部包裹的 Surface 写死了 shadowElevation 且未对外暴露，
 *    我们在 Haze 模式下采用自定义的无阴影 Surface（shadowElevation = 0.dp, tonalElevation = 0.dp），
 *    并通过 defaultMinSize(minHeight) 精确模拟官方 Snackbar 的最小高度（单行 Row 为 48.dp，换行 Column 为 68.dp），
 *    在彻底消除阴影黑边渗漏的同时，保证两种渲染模式在高度、排版上达到像素级的绝对一致。
 * 3. 颜色自适应：
 *    - Haze 模式：将容器颜色设为完全透明（Color.Transparent），依靠 HazeMaterials.regular() 提供的毛玻璃底层色板。
 *    - Material 模式：使用标准原生颜色与容器圆角，保证原生风格的极致性能与纯正体验。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurSnackbar(
    hazeState: HazeState,
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
    if (glassEffectMode == GlassEffectMode.Haze) {
        // 为每一次改动添加详尽的中文注释：Haze 模式下，自定义无阴影的 Surface，强制阴影高度与色彩高度为 0.dp，斩断投影伪像
        Surface(
            modifier = modifier
                .clip(shape)
                .hazeEffect(
                    state = hazeState,
                    style = dev.chrisbanes.haze.materials.HazeMaterials.regular()
                ),
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            // 为每一次改动添加详尽的中文注释：高精模拟官方 M3 Snackbar 布局，利用 defaultMinSize 强制约束最小高度，支持 actionOnNewLine 特性与 dismissAction
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
                            // 为每一次改动添加详尽的中文注释：根据是否有 Action 动态精准分配上下边距（10.dp 或 14.dp），防止 Row 与内部子项 Padding 发生叠加撑开
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
        // 为每一次改动添加详尽的中文注释：Material 原生模式，直接沿用官方标准 Snackbar，保留最佳底层绘制兼容性
        Snackbar(
            modifier = modifier,
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
