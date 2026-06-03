package com.viel.aplayer.ui.common

import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 详尽的中文注释：
 * 统一的屏幕与窗口尺寸类别自适应数据类。
 * 封装了基于 Android 官方 WindowSizeClass API 的核心自适应逻辑，
 * 用以在各种不同尺寸的设备（如手机、折叠屏、平板）以及不同的横竖屏方向下，
 * 统一控制和计算网格列数、内边距、布局类型等属性，避免硬编码判断。
 */
@Immutable
data class WindowClass(
    /**
     * 详尽的中文注释：官方的宽度尺寸分级类，包括 Compact、Medium、Expanded。
     */
    val widthSizeClass: WindowWidthSizeClass,
    /**
     * 详尽的中文注释：官方的高度尺寸分级类，包括 Compact、Medium、Expanded。
     */
    val heightSizeClass: WindowHeightSizeClass,
    /**
     * 详尽的中文注释：当前窗口是否处于横屏方向。
     */
    val isLandscape: Boolean,
    /**
     * 详尽的中文注释：当前屏幕或窗口的逻辑像素宽度，用于更精细的宽边距或对称分栏比例运算。
     */
    val screenWidthDp: Dp,
    /**
     * 详尽的中文注释：当前屏幕或窗口的逻辑像素高度，用于更精细的比例计算。
     */
    val screenHeightDp: Dp
) {
    /**
     * 详尽的中文注释：
     * 判断当前是否为平板设备或折叠大屏。
     * 根据官方自适应推荐规范，最窄宽度（widthSizeClass）为 Medium（宽度 >= 600dp）或 Expanded（宽度 >= 840dp）时，判定为大屏平板或折叠屏设备。
     */
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact

    /**
     * 详尽的中文注释：
     * 判断当前是否为宽屏显示状态。
     * 平板大屏设备（无论横竖屏）或处于横屏方向下的普通手机，均归为宽屏形态，以便共用卡片式或双栏流体布局。
     */
    val isWideScreen: Boolean
        get() = isTablet || isLandscape

    /**
     * 详尽 of 中文注释：
     * 自适应计算书籍列表的网格展示列数（columnsCount）：
     * 1. 当且仅当设备是平板（isTablet 为 true）且当前宽度达到 Expanded 级别（即宽度 >= 840dp，如平板横屏或大折叠屏展开）时，采用 3 列网格以提升大屏利用效率。
     * 2. 其他宽屏设备（如普通横屏手机或较窄的平板竖屏），统一展示为 2 列卡片，避免手机横屏 3 列导致内容过于拥挤。
     * 3. 手机竖屏状态（Compact 宽度）下展示为 1 列经典列表。
     */
    val columnsCount: Int
        get() = when {
            isTablet && widthSizeClass == WindowWidthSizeClass.Expanded -> 3
            isWideScreen -> 2
            else -> 1
        }

    /**
     * 详尽的中文注释：
     * 自适应获取屏幕两侧的横向业务内边距（Dp）。
     * 宽屏状态下采用 24.dp 边距，普通状态下采用 16.dp 边距，确保极致的视觉对齐和内容呼吸感。
     */
    val screenHorizontalPadding: Dp
        get() = if (isWideScreen) 24.dp else 16.dp

    /**
     * 详尽的中文注释：
     * 平板横屏双栏排版判定条件。
     * 只有当设备是平板（最窄宽度 >= 600dp）且当前处于横屏状态时，才启用高级的左右对称双栏控制板。
     */
    val isTabletLandscape: Boolean
        get() = isTablet && isLandscape

    companion object {
        /**
         * 详尽的中文注释：
         * 常用预设一：竖屏普通智能手机。
         * 宽度为 Compact（360dp），高度为 Medium（800dp），非横屏。
         */
        val PortraitPhone = WindowClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isLandscape = false,
            screenWidthDp = 360.dp,
            screenHeightDp = 800.dp
        )

        /**
         * 详尽的中文注释：
         * 常用预设二：横屏普通智能手机。
         * 宽度为 Medium（720dp），高度为 Compact（360dp），横屏状态。
         */
        val LandscapePhone = WindowClass(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Compact,
            isLandscape = true,
            screenWidthDp = 720.dp,
            screenHeightDp = 360.dp
        )

        /**
         * 详尽的中文注释：
         * 常用预设三：横屏平板设备。
         * 宽度为 Expanded（1280dp），高度为 Medium（800dp），横屏状态。
         */
        val TabletLandscape = WindowClass(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isLandscape = true,
            screenWidthDp = 1280.dp,
            screenHeightDp = 800.dp
        )
    }
}

/**
 * 详尽的中文注释：
 * 全局专用的 CompositionLocal 静态实例。
 * 允许在 Compose 页面渲染树中低耦合、高性能地检索当前窗口对应的自适应参数。
 */
val LocalWindowClass: ProvidableCompositionLocal<WindowClass> = staticCompositionLocalOf {
    // 详尽的中文注释：默认提供 PortraitPhone 实例，保证在 Preview 预览或未显式配置的极端情况下不引发闪退崩溃，实现完美的自愈降级。
    WindowClass.PortraitPhone
}

/**
 * 详尽的中文注释：
 * 在 Compose 组件树中感知物理窗口与配置变化，动态计算并构造 WindowClass 的 Composable 辅助函数。
 * 它能够精确对应官方 WindowSizeClass 级别的宽度高度判定：
 * - 宽度：小于 600dp 为 Compact，小于 840dp 为 Medium，大于等于 840dp 为 Expanded。
 * - 高度：小于 480dp 为 Compact，小于 900dp 为 Medium，大于等于 900dp 为 Expanded。
 */
@Composable
fun rememberWindowClass(): WindowClass {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp

    val widthSizeClass = when {
        widthDp < 600 -> WindowWidthSizeClass.Compact
        widthDp < 840 -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }

    val heightSizeClass = when {
        heightDp < 480 -> WindowHeightSizeClass.Compact
        heightDp < 900 -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }

    return remember(widthSizeClass, heightSizeClass, isLandscape, widthDp, heightDp) {
        WindowClass(
            widthSizeClass = widthSizeClass,
            heightSizeClass = heightSizeClass,
            isLandscape = isLandscape,
            screenWidthDp = widthDp.dp,
            screenHeightDp = heightDp.dp
        )
    }
}
