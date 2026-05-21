package com.viel.aplayer.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 详尽中文注释：
 * BlurModalBottomSheet —— 支持原生 Window 背景模糊的 BottomSheet（Android 12+ / API 31+）。
 *
 * 实现原理：
 * Material3 的 [ModalBottomSheet] 内部使用一个独立的系统 Dialog Window 托管内容，
 * 与 Compose [Dialog] 共享相同的 Window 创建机制。
 * 因此，在其内容 lambda 顶部调用 [ApplyWindowBlur]，即可利用相同的 context 链 unwrap
 * 逻辑定位到 BottomSheet 的 Window，并写入 FLAG_BLUR_BEHIND / blurBehindRadius 属性，
 * 实现 scrim 后景原生 GPU 模糊效果。
 *
 * 与 [ModalBottomSheet] 的关系：
 * 此组件是对 Material3 [ModalBottomSheet] 的薄封装，所有原始参数均透传，
 * 仅额外插入 [ApplyWindowBlur] 调用和模糊半径参数，调用方无需修改其他逻辑。
 *
 * 容器颜色策略：
 * 默认使用 surfaceContainerLow + 0.94f alpha（比 Dialog 稍高透明度），
 * 更适合 BottomSheet 大面积覆盖时的玻璃拟态视觉平衡。
 * 调用方可通过 [containerColor] 覆盖默认值。
 *
 * @param onDismissRequest 点击 scrim 或下滑关闭时的回调
 * @param blurBehindRadius BottomSheet"身后"（scrim 可见区域）的模糊半径，单位 px，默认 50px
 * @param sheetState BottomSheet 状态，控制展开/收起/半展开
 * @param shape BottomSheet 面板顶部圆角形状，默认使用 Material3 规范的 BottomSheetDefaults.ExpandedShape
 * @param containerColor 面板背景颜色，默认 surfaceContainerLow + 0.94f alpha
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
    blurBehindRadius: Int = 50,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
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
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
    ) {
        // 详尽中文注释：在 ModalBottomSheet 内容 lambda 顶部调用 ApplyWindowBlur。
        // 此时 LocalView.current 已指向 BottomSheet 内部的 AndroidComposeView，
        // 其 context 链同样包含 android.app.Dialog 实例（Material3 内部实现），
        // ApplyWindowBlur 通过 unwrap 链精准定位 Window 并设置模糊属性。
        ApplyWindowBlur(blurBehindRadius = blurBehindRadius)

        // 详尽中文注释：透传调用方提供的正文内容
        content()
    }
}
