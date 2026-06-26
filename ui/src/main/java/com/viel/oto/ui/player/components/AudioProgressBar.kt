package com.viel.oto.ui.player.components

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.theme.LocalDarkTheme
import com.viel.oto.ui.common.theme.OtoTheme

@Composable
fun AudioProgressBar(
    progress: () -> Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    bufferedProgress: () -> Float = { 0f },
    color: Color = MaterialTheme.colorScheme.primary,
    showKnob: Boolean = true,
    markers: List<Float> = emptyList(),
    enableProgressSemantics: Boolean = true,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)

    val trackColor = remember(color) { color.copy(alpha = 0.2f) }
    val markerColor = remember { Color.Black.copy(alpha = 0.3f) }
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 6.dp.toPx() } }
    val markerRadiusPx = remember(density) { with(density) { 2.dp.toPx() } }
    val knobRadiusPx = remember(density) { with(density) { 8.dp.toPx() } }
    val isDark = LocalDarkTheme.current
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val progressContentDescription = stringResource(R.string.playback_progress_content_description)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .graphicsLayer()
            .then(
                if (enableProgressSemantics) {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = progressContentDescription
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
                } else {
                    Modifier
                }
            )
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
        val currentProgress = progress().coerceIn(0f, 1f)
        val currentBufferedProgress = bufferedProgress().coerceAtLeast(currentProgress).coerceIn(0f, 1f)
        val activeWidth = width * currentProgress
        val bufferedWidth = width * currentBufferedProgress

        if (isBlur) {
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

        if (bufferedWidth > activeWidth) {
            if (isBlur) {
                val bufferedBrush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f))
                    } else {
                        listOf(Color.Black.copy(alpha = 0.16f), Color.Black.copy(alpha = 0.04f))
                    },
                    start = Offset(activeWidth, centerY),
                    end = Offset(bufferedWidth, centerY)
                )
                drawLine(
                    brush = bufferedBrush,
                    start = Offset(activeWidth, centerY),
                    end = Offset(bufferedWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            } else {
                drawLine(
                    color = color.copy(alpha = 0.35f),
                    start = Offset(activeWidth, centerY),
                    end = Offset(bufferedWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }

        markers.forEach { marker ->
            if (marker > 0f && marker < 1f) {
                val markerX = width * marker
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

        if (activeWidth > 0) {
            if (isBlur) {
                val borderGlowColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.30f)
                drawLine(
                    color = borderGlowColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx + with(density) { 1.dp.toPx() },
                    cap = StrokeCap.Round
                )
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

        if (showKnob) {
            if (isBlur) {
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
                drawCircle(
                    color = color,
                    radius = knobRadiusPx * 0.45f,
                    center = Offset(activeWidth, centerY)
                )
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
                     style = Stroke(width = with(density) { 1.dp.toPx() })
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
    OtoTheme {
        AudioProgressBar(
            progress = { 0.4f },
            onProgressChange = {},
            modifier = Modifier.padding(16.dp),
            markers = listOf(0.2f, 0.5f, 0.8f)
        )
    }
}
