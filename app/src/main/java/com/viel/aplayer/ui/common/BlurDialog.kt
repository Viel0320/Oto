package com.viel.aplayer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 详尽中文注释：
 * BlurDialog —— 支持原生 Window 背景模糊的通用浮层对话框（Android 12+ / API 31+）。
 *
 * 实现原理：
 * - 使用 Compose [Dialog] 而非 [androidx.compose.material3.AlertDialog]，以便直接访问底层 Window。
 * - 在 Dialog lambda 内部调用 [ApplyWindowBlur]（位于 WindowBlurHelper.kt），
 *   由其负责定位 Dialog Window 并写入 FLAG_BLUR_BEHIND / blurBehindRadius 属性。
 * - Window 背景置透明，让自定义圆角 Surface 正确渲染无遮挡。
 * - 面板颜色使用 surfaceContainerHigh + 0.92f alpha，与身后模糊叠加出玻璃拟态层次感。
 *
 * @param onDismissRequest 点击对话框外部或按系统返回键时的关闭回调
 * @param blurBehindRadius 对话框"身后"（scrim）区域的模糊半径，单位 px，默认 40px
 * @param scrollable 内容区域是否允许纵向滚动，内容较多时设为 true
 * @param content 对话框正文 Composable 内容
 */
@Composable
fun BlurDialog(
    onDismissRequest: () -> Unit,
    blurBehindRadius: Int = 40,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // 详尽中文注释：关闭平台默认宽度限制，由 widthIn 精确约束宽度
            usePlatformDefaultWidth = false,
            // 详尽中文注释：允许内容延伸至系统窗口内边距区域
            decorFitsSystemWindows = false
        )
    ) {
        // 详尽中文注释：在 Dialog lambda 内调用 ApplyWindowBlur，
        // 此时 LocalView 已指向 Dialog 自己的 AndroidComposeView，Window 定位准确。
        ApplyWindowBlur(blurBehindRadius = blurBehindRadius)

        // 详尽中文注释：全屏 Box 作为定位容器，padding 确保对话框与屏幕边缘保持安全间距
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            // 详尽中文注释：对话框面板 Surface。
            // - 28dp 圆角符合 Material 3 Dialog 规范
            // - surfaceContainerHigh + 0.92f alpha 与身后模糊形成玻璃拟态视觉层次
            // - tonalElevation = 6.dp：暗色模式下产生色调差，强化层次感
            // - shadowElevation = 8.dp：轻微投影强化悬浮感
            Surface(
                modifier = Modifier.widthIn(min = 280.dp, max = 460.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                // 详尽中文注释：按 scrollable 参数决定是否附加纵向滚动能力
                val scrollModifier = if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Box(modifier = scrollModifier) {
                    content()
                }
            }
        }
    }
}
