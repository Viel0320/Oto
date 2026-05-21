package com.viel.aplayer.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * 详尽中文注释：
 * BlurDialog —— 使用 Haze 重写后的通用毛玻璃浮层对话框。
 *
 * 实现原理：
 * - 调用方必须把同一个 [HazeState] 传给背景层的 hazeSource 与此处的 hazeEffect。
 * - 这能让 Dialog 即使运行在独立 Window 中，也通过 Haze 采样宿主 Compose 背景完成模糊。
 * - 面板颜色使用半透明 surfaceContainerHigh，Haze 1.7.2 只负责稳定采样和模糊，底色仍由 Surface 控制。
 *
 * @param onDismissRequest 点击对话框外部或按系统返回键时的关闭回调
 * @param hazeState 与背景 hazeSource 共用的状态容器
 * @param glassEffectMode 当前玻璃效果模式，Material 模式不挂载 Haze modifier
 * @param hazeBlurRadius Haze 背景模糊半径，单位 dp
 * @param scrollable 内容区域是否允许纵向滚动，内容较多时设为 true
 * @param content 对话框正文 Composable 内容
 */
@Composable
fun BlurDialog(
    onDismissRequest: () -> Unit,
    hazeState: HazeState,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由调用方从设置状态显式传入，避免 Dialog 内部私自声明默认值。
    glassEffectMode: GlassEffectMode,
    hazeBlurRadius: Dp = 160.dp,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    // 为每一次改动添加详尽的中文注释：Haze 模式沿用当前透明度配置；Material 模式使用不透明容器回到原生 Material 层次并停用模糊采样。
    val dialogContainerColor = if (glassEffectMode == GlassEffectMode.Haze) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // 详尽中文注释：关闭平台默认宽度限制，由 widthIn 精确约束宽度
            usePlatformDefaultWidth = false,
            // 详尽中文注释：允许内容延伸至系统窗口内边距区域
            decorFitsSystemWindows = false
        )
    ) {
        // 详尽中文注释：全屏 Box 作为定位容器。配置了 clickable 修饰符，
        // 在 usePlatformDefaultWidth = false 时替代失效的原生 Outside Touch，
        // 使得用户点击对话框外围模糊空白处时，能灵敏触发 onDismissRequest()。
        // 同时传递无波纹点击反馈以维持视觉的高级感。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onDismissRequest()
                }
                .padding(horizontal = 24.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            // 详尽中文注释：对话框面板 Surface。
            // - 采用系统 extraLarge 圆角符合 Material 3 Dialog 规范，支持主题自适应
            // - surfaceContainerHigh + 0.78f alpha 与 Haze 采样模糊形成玻璃拟态视觉层次
            // - tonalElevation = 6.dp：暗色模式下产生色调差，强化层次感
            // - shadowElevation = 8.dp：轻微投影强化悬浮感
            // - 关键改动：添加无波纹 clickable 以拦截并消费点击手势，阻止其错误向上穿透触发 dismiss 关闭
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 460.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // 详尽的中文注释：空操作，单纯用于拦截手势，防止点击对话框主体错误触发 dismiss
                    },
                shape = MaterialTheme.shapes.extraLarge,
                // 详尽中文注释：半透明底色让 Haze 模糊纹理透出，同时保留文字可读的 Material 3 层次。
                color = dialogContainerColor,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                // 详尽中文注释：按 scrollable 参数决定是否附加纵向滚动能力
                val scrollModifier = if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                // 为每一次改动添加详尽的中文注释：仅在 Haze 模式挂载 hazeEffect；Material 模式完全跳过采样，避免额外渲染成本。
                val glassModifier = if (glassEffectMode == GlassEffectMode.Haze) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        // 详尽中文注释：只指定模糊半径，透明玻璃底色继续交给外层 Surface，减少 Dialog 背景闪烁概率。
                        style = HazeStyle(tints = emptyList(), blurRadius = hazeBlurRadius)
                    )
                } else {
                    Modifier
                }
                Box(
                    modifier = scrollModifier.then(glassModifier)
                ) {
                    content()
                }
            }
        }
    }
}
