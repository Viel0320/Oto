package com.viel.aplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun AudioProgressBar(
    progress: () -> Float, // 优化：使用 Lambda 避免高频重组
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    showKnob: Boolean = true,
    markers: List<Float> = emptyList()
) {
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    
    // 缓存颜色和密度转换，避免在绘制时重复创建对象或计算
    val trackColor = remember(color) { color.copy(alpha = 0.2f) }
    val markerColor = remember { Color.Black.copy(alpha = 0.3f) }
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 6.dp.toPx() } }
    val markerWidthPx = remember(density) { with(density) { 2.dp.toPx() } }
    val knobRadiusPx = remember(density) { with(density) { 8.dp.toPx() } }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .graphicsLayer() // 隔离重绘，减少对父布局的影响
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

        // 绘制背景轨道
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
        
        // 绘制章节标记
        markers.forEach { marker ->
            if (marker > 0f && marker < 1f) {
                val markerX = width * marker
                drawLine(
                    color = markerColor,
                    start = Offset(markerX, centerY - strokeWidthPx / 2),
                    end = Offset(markerX, centerY + strokeWidthPx / 2),
                    strokeWidth = markerWidthPx
                )
            }
        }

        // 绘制激活进度
        drawLine(
            color = color,
            start = Offset(0f, centerY),
            end = Offset(activeWidth, centerY),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
        
        // 绘制进度球
        if (showKnob) {
            drawCircle(
                color = color,
                radius = knobRadiusPx,
                center = Offset(activeWidth, centerY)
            )
        }
    }
}

@Preview(showBackground = true)
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
