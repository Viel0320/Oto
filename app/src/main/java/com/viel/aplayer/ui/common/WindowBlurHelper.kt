package com.viel.aplayer.ui.common

import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

/**
 * 详尽中文注释：
 * WindowBlurHelper —— 应用于 Dialog/BottomSheet Window 的原生模糊底层工具函数（API 31+）。
 *
 * 设计动机：
 * Compose 的 [Dialog]、[androidx.compose.material3.ModalBottomSheet] 等悬浮层
 * 各自运行在独立的系统 Window 中，可通过对其 Window 设置以下属性实现原生 GPU 模糊：
 *   - [WindowManager.LayoutParams.FLAG_BLUR_BEHIND]：开启"身后模糊"渲染管线
 *   - [WindowManager.LayoutParams.blurBehindRadius]：控制 scrim 后景区域的模糊强度（px）
 *
 * 此 Composable 工具函数封装了 Window 定位与属性写入逻辑，供 [BlurDialog]、
 * [BlurModalBottomSheet] 等所有需要模糊效果的悬浮层组件复用，避免代码重复。
 *
 * Window 定位原理：
 * 在任意 Dialog/BottomSheet 的 Compose 内容 lambda 内部，[LocalView.current] 指向
 * 该悬浮层内部创建的 AndroidComposeView。其 context 链为：
 *   AndroidComposeView.context → ContextThemeWrapper(s) → android.app.Dialog
 * 通过逐层解包 [ContextWrapper] 即可精准找到对应的 [android.app.Dialog] 实例，
 * 进而取其 [android.view.Window]，不会误操作宿主 Activity 的 Window。
 *
 * 使用方式：
 * 在任意 Dialog/BottomSheet 内容 lambda 中，顶层调用此 Composable 即可：
 * ```
 * ModalBottomSheet(...) {
 *     ApplyWindowBlur(blurBehindRadius = 40)
 *     // 其余内容...
 * }
 * ```
 *
 * @param blurBehindRadius 悬浮层"身后"（可见 scrim 区域）的模糊半径，单位 px。
 *        推荐范围 20~80px。数值越大模糊越强。默认 40px（视感柔和、层次分明）。
 */
@Composable
fun ApplyWindowBlur(blurBehindRadius: Int = 40) {
    // 详尽中文注释：在悬浮层内容 lambda 中读取 LocalView，
    // 此时 LocalView 已由 Compose Dialog/BottomSheet 内部替换为悬浮层自己的 AndroidComposeView。
    val view = LocalView.current

    // 详尽中文注释：SideEffect 在 Compose 帧成功提交后同步执行（非挂起），
    // 此时悬浮层 Window 必然已完成创建与附着，可以安全修改 Window 属性。
    SideEffect {
        // 详尽中文注释：逐层 unwrap ContextWrapper，找到 android.app.Dialog 实例并取其 Window。
        // Material3 ModalBottomSheet 内部同样基于 Dialog 实现，此链路对两者均适用。
        var ctx = view.context
        var targetWindow: android.view.Window? = null
        while (ctx is ContextWrapper) {
            if (ctx is android.app.Dialog) {
                targetWindow = ctx.window
                break
            }
            ctx = ctx.baseContext
        }

        targetWindow?.let { win ->
            // 详尽中文注释：FLAG_BLUR_BEHIND — 通知 SurfaceFlinger 对此 Window 后方的内容执行模糊合成。
            // API 31+ 有效，低版本（本项目不存在，minSdk = 31）会被系统静默忽略。
            win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)

            // 详尽中文注释：blurBehindRadius — 身后模糊半径（px）。
            // 须在设置 FLAG_BLUR_BEHIND 之后写入 attributes，再整体赋回以触发 WindowManager 更新。
            val attrs = win.attributes
            attrs.blurBehindRadius = blurBehindRadius
            win.attributes = attrs

            // 详尽中文注释：将悬浮层 Window 自身背景设为完全透明，
            // 防止 PhoneWindow 默认白色矩形遮挡自定义圆角面板。
            // 对于 BottomSheet 可选：Material3 已为其 Window 设置透明背景；
            // 对于自定义 Dialog 则必须手动清除。
            win.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
}
