package com.viel.aplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
// 为每一次改动添加详尽的中文注释：使用 miuix-blur 的 Backdrop 机制 API 彻底替换旧的模糊库依赖，以实现高保真 textureBlur 噪点磨砂着色高密度模糊
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * 详尽中文注释：
 * BlurModalBottomSheet —— 使用 miuix-blur 重写后的毛玻璃 BottomSheet。
 *
 * 实现原理：
 * Material3 的 [ModalBottomSheet] 内部使用独立 Dialog Window 托管内容，我们需要
 * 将最外层 Activity 的 [LayerBackdrop] 传给此处的 backdrop 参数以采样背景，再由 drawBackdrop 绘制模糊。
 *
 * 与 [ModalBottomSheet] 的关系：
 * 此组件是对 Material3 [ModalBottomSheet] 的薄封装，所有原始参数均透传，
 * 仅把拖拽把手与正文统一包进 miuix-blur 稳定毛玻璃层，调用方无需改写内容结构。
 *
 * 容器颜色策略：
 * 默认使用 surfaceContainerLow + 0.78f alpha，更适合 BottomSheet 大面积覆盖时的玻璃拟态视觉平衡。
 *
 * @param onDismissRequest 点击 scrim 或下滑关闭时的回调
 * @param backdrop 与主渲染背景关联的模糊描述符状态机
 * @param glassEffectMode 当前玻璃效果模式，Material 模式不挂载模糊修饰符
 * @param sheetState BottomSheet 状态，控制展开/收起/半展开
 * @param shape BottomSheet 面板顶部圆角形状，默认使用 Material3 规范的 BottomSheetDefaults.ExpandedShape
 * @param containerColor 面板背景颜色，默认 surfaceContainerLow + 0.78f alpha
 * @param contentColor 内容默认前景色
 * @param tonalElevation 色调高程
 * @param scrimColor scrim 遮罩颜色（建议保持默认透明/半透明，模糊效果即为视觉遮罩）
 * @param dragHandle 顶部拖拽把手 Composable，默认 Material3 标准把手
 * @param contentWindowInsets BottomSheet 内容区的窗口内边距，默认不消费系统栏
 * @param modifier 修饰符
 * @param content BottomSheet 正文内容（ColumnScope）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlurModalBottomSheet(
    onDismissRequest: () -> Unit,
    backdrop: LayerBackdrop,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由调用方从设置状态显式传入，避免 BottomSheet 内部私自声明默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 8.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit
) {
    // 为每一次改动添加详尽的中文注释：获取当前系统的亮暗色主题状态，以实现 BottomSheet 自适应着色混合
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // 为每一次改动添加详尽的中文注释：MiuixBlur 模式下将外层 containerColor 设为透明，由内部毛玻璃 Box 统一渲染背景，避免渲染双重底色。修改引用至 MiuixBlur。
    val sheetContainerColor = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        Color.Transparent
    } else {
        containerColor
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = sheetContainerColor,
        contentColor = contentColor,
        // 为每一次改动添加详尽的中文注释：MiuixBlur 模式下自适应将高程设为 0.dp，彻底杜绝系统 RenderNode 在透明圆角边缘产生的重叠灰色阴影阴霾。修改引用至 MiuixBlur
        tonalElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else tonalElevation,
        scrimColor = scrimColor,
        // 详尽中文注释：将原本由 ModalBottomSheet 单独绘制的 dragHandle 移入模糊内容层，保证把手区域也拥有同一块毛玻璃背景。
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
    ) {
        // 为每一次改动添加详尽的中文注释：仅在 MiuixBlur 模式挂载 drawBackdrop 与半透明蒙版底色；Material 模式完全跳过毛玻璃修饰。将引用修改为新更名的 MiuixBlur
        val glassModifier = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = 60f, // thick -> 大范围深度模糊
                noiseCoefficient = 0.05f, // texture -> 高拟真漫反磨砂噪点
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(
                            color = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.76f), // colored -> 自适应色混
                            mode = BlurBlendMode.SrcOver
                        )
                    )
                )
            )
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(glassModifier)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 详尽中文注释：Material3 原 dragHandle slot 默认居中；移入模糊内容层后需要手动恢复 fillMaxWidth + Center 对齐。
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // 详尽中文注释：在同一个模糊面板内绘制拖拽把手，避免顶部把手区域和正文区域玻璃质感断层。
                    dragHandle?.invoke()
                }

                // 详尽中文注释：透传调用方提供的正文内容，业务层无需感知模糊内部包装。
                content()
            }
        }
    }
}
