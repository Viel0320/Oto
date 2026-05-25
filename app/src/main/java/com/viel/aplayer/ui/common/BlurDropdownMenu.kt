package com.viel.aplayer.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.viel.aplayer.data.store.GlassEffectMode
// 使用 miuix-blur 模糊库的 Backdrop 机制 API 彻底替换旧的模糊库依赖，以实现高保真 textureBlur 噪点磨砂着色高密度模糊
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * BlurDropdownMenu —— 支持 Material 原生菜单与 miuix-blur 毛玻璃菜单之间切换的通用 DropdownMenu 封装。
 *
 * 使用方式：
 * - 调用方在宿主页面根部维护同一个 [LayerBackdrop]。
 * - Material 模式只使用原生 [DropdownMenu]，不启用毛玻璃渲染管线。
 * - miuix-blur 模式在 DropdownMenu 自己的 content modifier 层挂载 textureBlur，并添加 0.78f 半透明底色，确保文字有良好对比度与极致设计美学。
 */
@Composable
fun BlurDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: LayerBackdrop,
    // 玻璃效果模式必须由调用方从设置状态显式传入，避免通用菜单内部私自声明默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    // 复用 Material3 默认菜单形状，保证模糊层和 DropdownMenu 外层 Surface 使用同一套圆角边界。
    val menuShape = MenuDefaults.shape
    // MiuixBlur 模式把外层 Surface 设为透明，避免 Surface 背景和 drawBackdrop 发生重叠渲染冲突。修改引用为新更名的 MiuixBlur。
    val menuContainerColor = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        Color.Transparent
    } else {
        MenuDefaults.containerColor
    }
    // 获取当前系统的亮暗色主题状态，以实现下拉菜单底色自适应着色混合
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // modifier 会被 Material3 应用到内部滚动 Column，使用 miuix-blur 就地绘制模糊与液态高光折光并涂覆半透明蒙版底色。将引用修改为新更名的 MiuixBlur
    val menuModifier = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = menuShape,
            blurRadius = 60f, // thick -> 厚模糊，提供极佳沉浸感
            noiseCoefficient = 0.05f, // texture -> 强磨砂噪点质感
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(
                        color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f), // 微调蒙版深度，以供底层氛围折光与高光显示
                        mode = BlurBlendMode.SrcOver
                    )
                )
            )
        )
        // 
        // 3. 链式追加斜向白色反射光掠覆盖层 (Specular Glare)，模拟真实水晶玻璃表面对光源的物理折射反光。
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.03f),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.06f)
                )
            ),
            shape = menuShape
        )
        // 
        // 4. 链式追加 1.dp 极细自适应渐变微光折射边框 (Refraction Edge)，大幅提升下拉菜单的品质与立体质感。
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
            shape = menuShape
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
        // MiuixBlur 模式不再让 Material tonal overlay 参与混色，避免菜单边缘出现和内容区不同的色调。修改引用为新更名的 MiuixBlur
        tonalElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else MenuDefaults.TonalElevation,
        // 自适应归零 Elevation，彻底杜绝菜单在透明视口下可能产生的 Android 系统级硬件影子重影
        shadowElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else MenuDefaults.ShadowElevation
    ) {
        // 透传原 DropdownMenuItem 内容，业务层只需要替换容器组件。
        content()
    }
}
