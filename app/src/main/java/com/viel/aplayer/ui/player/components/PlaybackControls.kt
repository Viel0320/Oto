package com.viel.aplayer.ui.player.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.player.PlaybackControlActions
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush

// 导入基础 background 修饰符，修复 miuix-blur 磨砂玻璃大圆钮的背景修饰符编译未解析引用问题
import androidx.compose.foundation.background

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    playbackSpeed: Float,
    selectedSleepTimer: Int,
    isSpeedManualMode: Boolean,
    actions: PlaybackControlActions,
    modifier: Modifier = Modifier,
    buttonColor: Color = MaterialTheme.colorScheme.primaryContainer,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    backdrop: LayerBackdrop? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Speed Toast logic (Debounced)
    var lastSpeed by remember { mutableFloatStateOf(playbackSpeed) }
    LaunchedEffect(playbackSpeed) {
        if (playbackSpeed != lastSpeed) {
            // 详尽的中文注释：将倍速选择的防抖延迟时间调整为 1500 毫秒（1.5秒），以获得更加充足的防连击防爆刷体验
            delay(1500) // Wait for 1.5s of inactivity
            val msg = if (playbackSpeed == 1.0f) "Playback speed reset" else "Playback speed: ${playbackSpeed}x"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            lastSpeed = playbackSpeed
        }
    }

    // Sleep Timer Toast logic (Debounced)
    var lastTimer by remember { androidx.compose.runtime.mutableIntStateOf(selectedSleepTimer) }
    LaunchedEffect(selectedSleepTimer) {
        if (selectedSleepTimer != lastTimer) {
            // 详尽的中文注释：将睡眠定时器时长选择的防抖延迟时间同样调整为 1500 毫秒（1.5秒），避免连按时弹窗刷屏
            delay(1500) // Wait for 1.5s of inactivity
            val msg = when (selectedSleepTimer) {
                0 -> "Sleep timer off"
                -1 -> "Sleep in 5 seconds"
                -2 -> "Stop at end of chapter"
                else -> "Sleep in $selectedSleepTimer minutes"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            lastTimer = selectedSleepTimer
        }
    }

    val contentColor = if (buttonColor.luminance() > 0.5f) Color.Black else Color.White

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = actions.onCyclePlaybackSpeed,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.onResetPlaybackSpeed()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (playbackSpeed == 1.0f && !isSpeedManualMode) {
                Icon(
                    Icons.Rounded.Speed,
                    contentDescription = "Playback Speed",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = "${playbackSpeed}x",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        IconButton(onClick = actions.onSkipBackward, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.Replay10,
                contentDescription = "Rewind 10 seconds",
                modifier = Modifier.size(32.dp)
            )
        }

        // 对齐 MiuixBlur，感知当前是否启用了磨砂高斯模糊效果，统一重命名逻辑引用
        val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur

        if (isBlur) {
            // 
            // miuix-blur 磨砂效果激活时，将播放暂停按钮升级为清透灵动的磨砂玻璃大圆钮 Surface。
            // 彻底去除对 drawBackdrop 实时采样的物理依赖，在播放器自带的 blur(64.dp) 超强大半径氛围模糊背景之上，
            // 级联自适应亮/暗色调的半透明圆形蒙版底色，并在此处结合自适应本地声明 0.5.dp 微光银丝描边。
            // 这在视觉层面上构建出极佳的 iOS 级轮廓光实体呼吸感，且彻底消除了高通 Vulkan 驱动在平移变换时的 Feedback Loop 闪退死锁。
            val playPauseShape = CircleShape
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            // 
            // 在 miuix-blur 磨砂效果下，将播放按钮改造为高透液态玻璃物理圆钮。
            // 1. 如果 backdrop 采样源存在，使用 textureBlur 对大圆钮进行物理高斯模糊渲染，添加细腻磨砂噪声；
            // 2. 链式覆盖高光斜向线性渐变 Specular Glare 层，形成晶莹的水滴反光面；
            // 3. 链式追加 1.dp 极细自适应 Refraction Edge 折射渐变边框，重塑 3D 精致轮廓。
            // 4. 若为 null，优雅安全降级回无描边的半透材质背景以维持极致稳定。
            val glassModifier = Modifier
                .size(80.dp)
                .let { modifier ->
                    if (backdrop != null) {
                        modifier.textureBlur(
                            backdrop = backdrop,
                            shape = playPauseShape,
                            blurRadius = 60f,
                            noiseCoefficient = 0.05f,
                            colors = BlurColors(
                                blendColors = listOf(
                                    BlendColorEntry(
                                        color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f),
                                        mode = BlurBlendMode.SrcOver
                                    )
                                )
                            )
                        )
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.12f),
                                    Color.White.copy(alpha = 0.03f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.06f)
                                )
                            ),
                            shape = playPauseShape
                        )
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
                            shape = playPauseShape
                        )
                    } else {
                        modifier
                            .clip(playPauseShape)
                            .background(
                                if (isDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.45f)
                            )
                    }
                }
            Surface(
                onClick = actions.onPlayPauseClick,
                modifier = glassModifier,
                shape = playPauseShape,
                color = Color.Transparent,
                border = null, // 完全交由上方的渐变 border 修饰符进行渲染
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            // Material 默认模式下维持原本色彩的 FilledIconButton 实色设计
            FilledIconButton(
                onClick = actions.onPlayPauseClick,
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = buttonColor,
                    contentColor = contentColor
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) {
                        Icons.Rounded.Pause
                    } else {
                        Icons.Rounded.PlayArrow
                    },
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        IconButton(onClick = actions.onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.Forward30,
                contentDescription = "Forward 30 seconds",
                modifier = Modifier.size(32.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = actions.onCycleSleepTimer,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.onCancelSleepTimer()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selectedSleepTimer == 0) {
                Icon(
                    Icons.Rounded.Snooze,
                    contentDescription = "Sleep Timer",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                val displayText = when (selectedSleepTimer) {
                    -1 -> "5s"
                    -2 -> "Ch"
                    else -> "${selectedSleepTimer}m"
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Preview(apiLevel = 36)
@Composable
fun PlaybackControlsPreview() {
    APlayerTheme {
        Surface {
            PlaybackControls(
                isPlaying = false,
                playbackSpeed = 1.0f,
                selectedSleepTimer = 0,
                isSpeedManualMode = false,
                actions = PlaybackControlActions(),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}