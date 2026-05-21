package com.viel.aplayer.ui.common

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * 详尽中文注释：
 * BlurModalBottomSheet —— 使用 Haze 重写后的毛玻璃 BottomSheet。
 *
 * 实现原理：
 * Material3 的 [ModalBottomSheet] 内部使用独立 Dialog Window 托管内容，Haze 需要调用方
 * 在宿主背景层挂载同一个 [HazeState] 的 hazeSource，再由本组件内部的 hazeEffect 采样模糊。
 *
 * 与 [ModalBottomSheet] 的关系：
 * 此组件是对 Material3 [ModalBottomSheet] 的薄封装，所有原始参数均透传，
 * 仅把拖拽把手与正文统一包进 Haze 1.7.2 稳定毛玻璃层，调用方无需改写内容结构。
 *
 * 容器颜色策略：
 * 默认使用 surfaceContainerLow + 0.9f alpha，
 * 更适合 BottomSheet 大面积覆盖时的玻璃拟态视觉平衡。
 * 调用方可通过 [containerColor] 覆盖默认值。
 *
 * @param onDismissRequest 点击 scrim 或下滑关闭时的回调
 * @param hazeState 与背景 hazeSource 共用的状态容器
 * @param glassEffectMode 当前玻璃效果模式，Material 模式不挂载 Haze modifier
 * @param sheetState BottomSheet 状态，控制展开/收起/半展开
 * @param shape BottomSheet 面板顶部圆角形状，默认使用 Material3 规范的 BottomSheetDefaults.ExpandedShape
 * @param containerColor 面板背景颜色，默认 surfaceContainerLow + 0.9f alpha
 * @param contentColor 内容默认前景色
 * @param tonalElevation 色调高程
 * @param scrimColor scrim 遮罩颜色（建议保持默认透明/半透明，模糊效果即为视觉遮罩）
 * @param dragHandle 顶部拖拽把手 Composable，默认 Material3 标准把手
 * @param contentWindowInsets BottomSheet 内容区的窗口内边距，默认不消费系统栏
 * @param modifier 修饰符
 * @param content BottomSheet 正文内容（ColumnScope）
 */
// 为每一次改动添加详尽的中文注释：BottomSheet 同时 OptIn Material3 与官方 haze-materials API，直接使用官方模板预设。
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurModalBottomSheet(
    onDismissRequest: () -> Unit,
    hazeState: HazeState,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由调用方从设置状态显式传入，避免 BottomSheet 内部私自声明默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 1f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 8.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        // 详尽中文注释：将原本由 ModalBottomSheet 单独绘制的 dragHandle 移入 Haze 内容层，保证把手区域也拥有同一块毛玻璃背景。
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
    ) {
        // 为每一次改动添加详尽的中文注释：仅在 Haze 模式挂载 hazeEffect；Material 模式保留 ModalBottomSheet 原生容器、scrim 与 tonalElevation。
        val glassModifier = if (glassEffectMode == GlassEffectMode.Haze) {
            Modifier.hazeEffect(
                state = hazeState,
                // 为每一次改动添加详尽的中文注释：BottomSheet 直接调用官方 HazeMaterials.regular()，避免项目内中间层隐藏真实模板来源。
                style = dev.chrisbanes.haze.materials.HazeMaterials.regular()
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
                // 详尽中文注释：Material3 原 dragHandle slot 默认居中；移入 Haze 内容层后需要手动恢复 fillMaxWidth + Center 对齐。
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // 详尽中文注释：在同一个 Haze 面板内绘制拖拽把手，避免顶部把手区域和正文区域玻璃质感断层。
                    dragHandle?.invoke()
                }

                // 详尽中文注释：透传调用方提供的正文内容，业务层无需感知 Haze 内部包装。
                content()
            }
        }
    }
}
