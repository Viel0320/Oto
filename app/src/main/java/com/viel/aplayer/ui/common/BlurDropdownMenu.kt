package com.viel.aplayer.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * 为每一次改动添加详尽的中文注释：
 * BlurDropdownMenu —— 支持 Material 原生菜单与 Haze 毛玻璃菜单之间切换的通用 DropdownMenu 封装。
 *
 * 使用方式：
 * - 调用方在菜单背后的宿主容器上用同一个 [HazeState] 挂载 hazeSource。
 * - Material 模式只使用原生 [DropdownMenu]，不启用 Haze 渲染管线。
 * - Haze 模式在 DropdownMenu 自己的 content modifier 层挂载 hazeEffect，覆盖 Material 默认上下边缘 padding。
 */
@Composable
fun BlurDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    hazeState: HazeState,
    // 为每一次改动添加详尽的中文注释：未显式传入时默认 Material，和全局默认视觉效果保持一致。
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    hazeBlurRadius: Dp = 160.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    // 为每一次改动添加详尽的中文注释：复用 Material3 默认菜单形状，保证 Haze 层和 DropdownMenu 外层 Surface 使用同一套圆角边界。
    val menuShape = MenuDefaults.shape
    // 为每一次改动添加详尽的中文注释：Haze 模式把外层 Surface 设为透明，避免 Surface 背景和 hazeEffect 背景在上下边缘产生两套颜色。
    val menuContainerColor = if (glassEffectMode == GlassEffectMode.Haze) {
        Color.Transparent
    } else {
        MenuDefaults.containerColor
    }
    // 为每一次改动添加详尽的中文注释：Haze 模式使用稳定的菜单底色作为 HazeStyle.backgroundColor，让边缘和内容区由同一层绘制。
    val hazeBackgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
    // 为每一次改动添加详尽的中文注释：modifier 会被 Material3 应用到内部滚动 Column 且位于默认上下 padding 之前，因此能覆盖菜单顶部和底部边缘。
    val menuModifier = if (glassEffectMode == GlassEffectMode.Haze) {
        Modifier
            .clip(menuShape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = hazeBackgroundColor,
                    tints = emptyList(),
                    blurRadius = hazeBlurRadius
                )
            )
    } else {
        Modifier
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.then(menuModifier),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = menuShape,
        containerColor = menuContainerColor,
        // 为每一次改动添加详尽的中文注释：Haze 模式不再让 Material tonal overlay 参与混色，避免菜单边缘出现和内容区不同的色调。
        tonalElevation = if (glassEffectMode == GlassEffectMode.Haze) 0.dp else MenuDefaults.TonalElevation,
        shadowElevation = MenuDefaults.ShadowElevation
    ) {
        // 为每一次改动添加详尽的中文注释：透传原 DropdownMenuItem 内容，业务层只需要替换容器组件。
        content()
    }
}
