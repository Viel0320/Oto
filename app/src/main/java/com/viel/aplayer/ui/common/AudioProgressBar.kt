package com.viel.aplayer.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun AudioProgressBar(
    progress: () -> Float, // 优化：使用 Lambda 避免高频重组
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    showKnob: Boolean = true,
    markers: List<Float> = emptyList(),
    // 新增玻璃视效选择模式参数，以开启极具拟物水滴水晶质感的高阶进度条
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    
    // 缓存颜色和密度转换，避免在绘制时重复创建对象或计算
    val trackColor = remember(color) { color.copy(alpha = 0.2f) }
    val markerColor = remember { Color.Black.copy(alpha = 0.3f) }
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 6.dp.toPx() } }
    // 新增章节标记点小球的半径参数，使其完美且精致地嵌入在轨道中
    val markerRadiusPx = remember(density) { with(density) { 2.dp.toPx() } }
    val knobRadiusPx = remember(density) { with(density) { 8.dp.toPx() } }
    val isDark = isSystemInDarkTheme()
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    // 
    // 在 Composable 上下文提取 secondary 和 tertiary 次/三级色，缓存为局部非 Composable 变量。
    // 这将完美修复 DrawScope 内部直接调用 @Composable 属性而引发的编译段错误。
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .graphicsLayer() // 隔离重绘，减少对父布局的影响
            // M-17 修复 — 补充无障碍语义节点
            // 提供 progressBarRangeInfo 以使 TalkBack 识别进度值与范围；
            // setProgress 自定义动作允许无障碍服务以编程方式设置进度。
            .semantics(mergeDescendants = true) {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = progress(),
                    range = 0f..1f,
                    steps = 0
                )
                setProgress { newValue ->
                    currentOnProgressChange(newValue.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    currentOnProgressChange(newProgress)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        currentOnProgressChange(newProgress)
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        val currentProgress = progress() // 仅在绘制阶段读取进度
        val activeWidth = width * currentProgress

        // 1. 绘制背景轨道 (未播放部分)
        if (isBlur) {
            // 
            // 将未播放底轨轨线 (Track Brush) 的半透明色调做极致降噪与清亮化微调，
            // 深色模式下从 15% -> 5% 极致下调至 8% -> 2%，浅色模式下从 12% -> 3% 极致下调至 6% -> 1%，
            // 彻底保障底轨轨道呈现出空灵通透、薄如蝉翼的微弱磨砂质感。
            val trackBrush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))
                } else {
                    listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.01f))
                },
                start = Offset(0f, centerY),
                end = Offset(width, centerY)
            )
            drawLine(
                brush = trackBrush,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
        } else {
            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
        }
        
        // 2. 绘制章节标记 (水晶小球化升级)
        markers.forEach { marker ->
            if (marker > 0f && marker < 1f) {
                val markerX = width * marker
                // 
                // 章节标记在 miuix-blur 磨砂状态下全新升级为自适应“微雕折光水晶小球”，镶嵌于 6.dp 轨道中央，
                // 深色模式下绘制 35% 晶莹白，浅色下绘制 20% 玄墨半透，实现与滑块 Knob 大小相得益彰的协调拟物感。
                val currentMarkerColor = if (isBlur) {
                    if (isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.20f)
                } else {
                    markerColor
                }
                drawCircle(
                    color = currentMarkerColor,
                    radius = markerRadiusPx,
                    center = Offset(markerX, centerY)
                )
            }
        }

        // 3. 绘制已播放进度轨道
        if (activeWidth > 0) {
            if (isBlur) {
                // 
                // 将三层立体水晶已播管整体调亮调薄以响应“更加透明一些”的要求：
                // (a) 底层：绘制略宽的外围折射描边线，深色模式透明度由 0.22 调薄降至 0.12，浅色模式由 0.55 降至 0.30，杜绝浮噪感。
                val borderGlowColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.30f)
                drawLine(
                    color = borderGlowColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx + with(density) { 1.dp.toPx() },
                    cap = StrokeCap.Round
                )
                // (b) 中间层：绘制彩色流光已播液体水柱轨，其三色流动渐变透明度参数由原先的 55%/45%/50% 均调薄折半至 28%/18%/23%，
                //     使其在保持冷暖流光流动感的同时，能够与后方的毛玻璃环境发生更通透、更清澈的物理融合。
                val progressBrush = Brush.linearGradient(
                    colors = listOf(
                        color.copy(alpha = 0.28f),
                        secondaryColor.copy(alpha = 0.18f),
                        tertiaryColor.copy(alpha = 0.23f)
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY)
                )
                drawLine(
                    brush = progressBrush,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
                // (c) 顶层内嵌：白色反光核管线的透明度从原先的 55%/10%/35% 微降至 35%/0.05%/20%，实现极致清爽透明的流光反光效果。
                val innerGlowBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.20f)
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY)
                )
                drawLine(
                    brush = innerGlowBrush,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx * 0.38f,
                    cap = StrokeCap.Round
                )
            } else {
                drawLine(
                    color = color,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }
        
        // 4. 绘制进度小球 (Knob)
        if (showKnob) {
            if (isBlur) {
                // 
                // 在 miuix-blur 模式下，将进度小滑块圆球全新升级重构为玻璃微缩珠子效果：
                // (a) 绘制微光半透的径向渐变毛斯磨砂小球底色
                val knobBgBrush = Brush.radialGradient(
                    colors = if (isDark) {
                        listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.60f))
                    },
                    center = Offset(activeWidth, centerY),
                    radius = knobRadiusPx
                )
                drawCircle(
                    brush = knobBgBrush,
                    radius = knobRadiusPx,
                    center = Offset(activeWidth, centerY)
                )
                // (b) 在珠子中心绘制主色调极其润泽的“发光核 (Luminous Nucleus)”
                drawCircle(
                    color = color,
                    radius = knobRadiusPx * 0.45f,
                    center = Offset(activeWidth, centerY)
                )
                // (c) 链式在外围套上一圈 1.dp 极细微光折射渐变描边 (Refraction Edge)
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = if (isDark) {
                            listOf(Color.White.copy(alpha = 0.45f), Color.Transparent, Color.White.copy(alpha = 0.15f))
                        } else {
                            listOf(Color.White.copy(alpha = 0.90f), Color.Transparent, Color.White.copy(alpha = 0.40f))
                        },
                        start = Offset(activeWidth - knobRadiusPx, centerY - knobRadiusPx),
                        end = Offset(activeWidth + knobRadiusPx, centerY + knobRadiusPx)
                     ),
                     radius = knobRadiusPx,
                     center = Offset(activeWidth, centerY),
                     style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 1.dp.toPx() })
                )
            } else {
                drawCircle(
                    color = color,
                    radius = knobRadiusPx,
                    center = Offset(activeWidth, centerY)
                )
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun AudioProgressBarPreview() {
    APlayerTheme {
        AudioProgressBar(
            progress = { 0.4f },
            onProgressChange = {},
            modifier = Modifier.padding(16.dp),
            markers = listOf(0.2f, 0.5f, 0.8f)
        )
    }
}