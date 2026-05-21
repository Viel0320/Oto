package com.viel.aplayer.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * 为每一次改动添加详尽的中文注释：
 * BlurDropdownMenu —— 支持 Material 原生菜单与 Haze 毛玻璃菜单之间切换的通用 DropdownMenu 封装。
 *
 * 使用方式：
 * - 调用方在菜单背后的宿主容器上用同一个 [HazeState] 挂载 hazeSource。
 * - Material 模式只使用原生 [DropdownMenu]，不启用 Haze 渲染管线。
 * - Haze 模式在 DropdownMenu 自己的 content modifier 层挂载 hazeEffect，覆盖 Material 默认上下边缘 padding。
 */
// 为每一次改动添加详尽的中文注释：组件直接 OptIn 官方 haze-materials API，Haze 模板不再通过项目内包装对象转发。
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    hazeState: HazeState,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由调用方从设置状态显式传入，避免通用菜单内部私自声明默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
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
    // 为每一次改动添加详尽的中文注释：modifier 会被 Material3 应用到内部滚动 Column，并直接套用官方 HazeMaterials.regular() 模板。
    val menuModifier = if (glassEffectMode == GlassEffectMode.Haze) {
        Modifier
            .clip(menuShape)
            .hazeEffect(
                state = hazeState,
                // 为每一次改动添加详尽的中文注释：DropdownMenu 直接调用官方 HazeMaterials.regular()，让模板来源在组件里可见。
                style = dev.chrisbanes.haze.materials.HazeMaterials.regular()
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
