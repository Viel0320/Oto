package com.viel.aplayer.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 详尽的中文注释：项目全局共享的“高雅白羽雾化”毛玻璃统一视觉规范预设模板。
 * 集中管理 Haze 材质预设、蒙版背景色以及细微白边边框，确保各模块（如详情页、编辑页、播放器等）毛玻璃组件在视觉层面上具备极致的呼吸感和浑然一体的质感。
 */
@OptIn(dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class)
object HazePresets {
    // 采用 @Composable 属性 Getter 动态获取 thick 材质，提供高度清透且具有优秀漫反射特征 of 毛玻璃基底
    val HazeStyle: dev.chrisbanes.haze.HazeStyle
        @Composable
        get() = dev.chrisbanes.haze.materials.HazeMaterials.thick()
    
    // 15% 透明度的极淡乳白蒙版，用来确立几何面域实体感，有效规避文字穿帮
    val BackgroundColor = Color.White.copy(alpha = 0.15f)
    
    // 0.5.dp 极细白透光泽描边，在高分辨率屏幕下构建灵动的轮廓光效果
    val BorderWidth = 0.5.dp
    
    // 30% 透明度白透描边色
    val BorderColor = Color.White.copy(alpha = 0.3f)
    
    // 统一的边框描边
    val Border = BorderStroke(BorderWidth, BorderColor)
}
