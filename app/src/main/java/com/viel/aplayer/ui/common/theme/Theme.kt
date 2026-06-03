package com.viel.aplayer.ui.common.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// =====================================================================
// M-21 修复 — 完整的 DarkColorScheme，
// 补齐了在 dynamicColor=false 时的 secondary, tertiary, error 及其 container 角色。
// =====================================================================
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = SurfaceDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceContainerDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark
)

// =====================================================================
// M-21 修复 — 完整的 LightColorScheme，
// 补齐了在 dynamicColor=false 时的 secondary, tertiary, error 及其 container 角色。
// =====================================================================
private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = SurfaceLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = OnSurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight
)

@Composable
fun APlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 详尽的中文注释：使用 findActivity 递归检索 Activity 宿主以获得状态栏 Window 控制权；
            // 这避免了在 Compose Preview 预览、Glance 小组件或者次要嵌入式视图中 view.context 并非 Activity 导致强转崩溃的隐患
            val activity = view.context.findActivity()
            if (activity != null) {
                val window = activity.window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    // 详尽的中文注释：
    // 感知当前窗口/物理屏幕的配置与像素尺寸变化，自适应推导并产生相应的 WindowClass 实例。
    // 使用 CompositionLocalProvider 将该实例作为全局 LocalWindowClass 提供，
    // 使得整棵界面树下的所有子 Composable 界面与各个 Compose Previews 均能零阻碍地共享自适应逻辑。
    val windowClass = rememberWindowClass()
    CompositionLocalProvider(
        LocalWindowClass provides windowClass
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * 详尽的中文注释：沿 ContextWrapper 上下文包装器链条递归向上查找，直至发现真实的宿主 Activity 实例；找不到则安全返回 null
 */
private tailrec fun Context.findActivity(): Activity? {
    if (this is Activity) return this
    return if (this is ContextWrapper) {
        baseContext.findActivity()
    } else {
        null
    }
}