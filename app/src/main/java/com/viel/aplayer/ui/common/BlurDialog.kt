package com.viel.aplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import com.viel.aplayer.data.store.GlassEffectMode
// 使用 miuix-blur 的 Backdrop 机制 API 彻底替换旧的模糊库依赖，以实现高保真 textureBlur 噪点磨砂着色高密度模糊
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * 
 * BlurDialog —— 使用 miuix-blur 重写后的通用毛玻璃浮层对话框。
 *
 * 实现原理：
 * - 调用方将最外层 Activity 的 LayerBackdrop 传给此处的 backdrop 参数。
 * - drawBackdrop 修饰符会就地渲染出基于该 LayerBackdrop 的高阶毛玻璃质感。
 * - 容器面板通过 0.78f 极佳半透明底色，与底下的模糊图层交织，达到极致清透、极佳设计感的呼吸美学。
 *
 * @param onDismissRequest 点击对话框外部或按系统返回键时的关闭回调
 * @param backdrop 与主渲染背景关联的模糊描述符状态机
 * @param glassEffectMode 当前悬浮层玻璃效果模式，Material 模式不挂载模糊修饰符
 * @param scrollable 内容区域是否允许纵向滚动，内容较多时设为 true
 * @param content 对话框正文 Composable 内容
 */
@Composable
fun BlurDialog(
    onDismissRequest: () -> Unit,
    backdrop: LayerBackdrop,
    // 玻璃效果模式必须由调用方从设置状态显式传入，避免 Dialog 内部私自声明默认值。
    glassEffectMode: GlassEffectMode,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    // 获取当前系统的亮暗色主题状态，以实现对话框底色自适应着色混合
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // 关闭 platform 默认宽度限制，由 widthIn 精确约束宽度
            usePlatformDefaultWidth = false,
            // 允许内容延伸至系统窗口内边距区域
            decorFitsSystemWindows = false
        )
    ) {
        // 全屏 Box 作为定位容器。配置了 clickable 修饰符，
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
            // 仅在 MiuixBlur 模式挂载高阶 textureBlur 质感磨砂与液态玻璃折光效果；Material 模式完全跳过采样以绝缘开销。
            val glassModifier = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
                val dialogShape = MaterialTheme.shapes.extraLarge
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = dialogShape,
                    blurRadius = 80f, // thick -> 进一步拉大模糊半径以提供更宽的混色渐变
                    noiseCoefficient = 0.05f, // texture -> 加强噪点以呈现真实的磨砂漫反纹理
                    colors = BlurColors(
                        blendColors = listOf(
                            BlendColorEntry(
                                color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f), // 微调蒙版深度，以供底层氛围折光与高光显示
                                mode = BlurBlendMode.SrcOver
                            )
                        ),
                        brightness = if (isDark) -0.12f else -0.05f, // 调低亮度，削弱底层高亮反差
                        contrast = 0.65f, // 大幅压缩对比度，抹平 Recently Added 列表物理硬切分界线
                        saturation = 1.0f
                    )
                )
                // 
                // 3. 链式追加斜向白色反射光掠覆盖层 (Specular Glare)，重塑水晶般剔透的 3D 折面反射。
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.03f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    shape = dialogShape
                )
                // 
                // 4. 链式追加 1.dp 极细自适应渐变微光折射边框 (Refraction Edge)，极大增强弹窗面板的悬浮感与视觉豪华度。
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
                    shape = dialogShape
                )
            } else {
                Modifier
            }

            // 对话框面板 Surface。
            // - 采用系统 extraLarge 圆角符合 Material 3 Dialog 规范，支持主题自适应
            // - tonalElevation = 6.dp：暗色模式下产生色调差，强化层次感
            // - shadowElevation = 8.dp：轻微投影强化悬浮感
            // - 关键改动：添加无波纹 clickable 以拦截并消费点击手势，阻止其错误向上穿透触发 dismiss 关闭
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 460.dp)
                    .then(glassModifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // 空操作，单纯用于拦截手势，防止点击对话框主体错误触发 dismiss
                    },
                shape = MaterialTheme.shapes.extraLarge,
                // MiuixBlur 模式下设为透明以展现完美的着色器材质模糊；Material 模式使用不透明容器。
                color = if (glassEffectMode == GlassEffectMode.MiuixBlur) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                // 
                // 在 MiuixBlur 模式下，由于 Surface 设为完全透明，必须硬性把 elevation 投影调为 0.dp。
                // 否则 Android 系统的 RenderNode 阴影投影垫片会在透明底层产生极其难看的硬件级灰色“边缘残影/重影”泄露。
                tonalElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else 6.dp,
                shadowElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else 8.dp
            ) {
                // 按 scrollable 参数决定是否附加纵向滚动能力
                val scrollModifier = if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Box(
                    modifier = scrollModifier
                ) {
                    content()
                }
            }
        }
    }
}
