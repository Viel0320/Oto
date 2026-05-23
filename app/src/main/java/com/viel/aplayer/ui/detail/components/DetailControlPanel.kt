package com.viel.aplayer.ui.detail.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.formatFileSize
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.detail.DetailUiState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

/**
 * 为每一次改动添加详尽的中文注释：
 * 封装详情页的操作控制面板 (DetailControlPanel)。
 * 整合了元数据标签组 (DetailInfoChip)、播放主控制按钮以及物理文件路径显示。
 * 支持根据 [isLandscape] 自动调整高度、圆角及间距，并完美适配 miuix-blur 磨砂玻璃效果。
 */
@Composable
fun DetailControlPanel(
    book: BookEntity?,
    uiState: DetailUiState,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop?,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    val isDark = isSystemInDarkTheme()
    val localBlurBackgroundColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
    val localBlurBorderColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.12f)
    val localBlurBorder = androidx.compose.foundation.BorderStroke(0.5.dp, localBlurBorderColor)
    val displayProgress = uiState.displayProgressPercent

    val buttonHeight = if (isLandscape) 48.dp else 56.dp
    val cornerRadius = if (isLandscape) 12.dp else 16.dp
    val chipSpacing = if (isLandscape) 8.dp else 10.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. 元数据标签组
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(chipSpacing, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DetailInfoChip(
                icon = Icons.Rounded.Event,
                value = book?.year?.takeIf { it.isNotBlank() } ?: "Unknown",
                glassEffectMode = glassEffectMode,
                backdrop = backdrop
            )
            DetailInfoChip(
                icon = Icons.Rounded.Timelapse,
                value = formatTime(book?.totalDurationMs ?: 0L),
                glassEffectMode = glassEffectMode,
                backdrop = backdrop
            )
            if ((book?.totalFileSize ?: 0L) > 0) {
                DetailInfoChip(
                    icon = Icons.Rounded.Storage,
                    value = formatFileSize(book?.totalFileSize ?: 0L),
                    glassEffectMode = glassEffectMode,
                    backdrop = backdrop
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. 播放主控制按钮
        if (isBlur && uiState.isAvailable) {
            Surface(
                onClick = {
                    onPlayPressed()
                    onPlayClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(buttonHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(cornerRadius) },
                                effects = { blur(20f) }
                            )
                        } else Modifier
                    ),
                shape = RoundedCornerShape(cornerRadius),
                color = localBlurBackgroundColor,
                border = localBlurBorder,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (displayProgress > 0) Icons.Rounded.History else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(if (isLandscape) 20.dp else 24.dp)
                    )
                    Spacer(modifier = Modifier.width(if (isLandscape) 6.dp else 8.dp))
                    Text(
                        text = if (displayProgress > 0) "Continue at $displayProgress%" else "Start Listening",
                        style = if (isLandscape) {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        } else {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        },
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Button(
                onClick = { 
                    if (uiState.isAvailable) {
                        onPlayPressed()
                        onPlayClick()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(buttonHeight),
                shape = RoundedCornerShape(cornerRadius),
                colors = if (uiState.isAvailable) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            ) {
                Icon(
                    imageVector = if (!uiState.isAvailable) Icons.Rounded.Storage 
                    else if (displayProgress > 0) Icons.Rounded.History 
                    else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(if (isLandscape) 20.dp else 24.dp)
                )
                Spacer(modifier = Modifier.width(if (isLandscape) 6.dp else 8.dp))
                Text(
                    text = if (!uiState.isAvailable) "File not found"
                           else if (displayProgress > 0) "Continue at $displayProgress%" 
                           else "Start Listening",
                    style = if (isLandscape) {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    } else {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    }
                )
            }
        }

        // 3. 物理文件路径显示
        if (uiState.fullSourcePath.isNotEmpty()) {
            Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))
            Text(
                text = uiState.fullSourcePath,
                style = if (isLandscape) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


/**
 * 为每一次改动添加详尽的中文注释：
 * 重构后的详情元数据卡片 (DetailInfoChip)。
 * 完美支持在 miuix-blur 模式下动态应用“高雅白羽雾化”设计规范，同时在传统不透明模式下退回为原生 Material3 经典微光描边，确保零额外开销。
 */
@Composable
fun DetailInfoChip(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    // 为每一次改动添加详尽的中文注释：传入全局玻璃效果模式，默认值设为已存在的 GlassEffectMode.Material
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // 为每一次改动添加详尽的中文注释：传入全局 Backdrop 背景模糊状态
    backdrop: LayerBackdrop? = null
) {
    // 为每一次改动添加详尽的中文注释：感知新更名的 MiuixBlur 磨砂玻璃模式是否已被开启且采样源不为空
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null

    // 为每一次改动添加详尽的中文注释：在 DetailInfoChip 内部基于当前 system/应用亮暗主题自适应配置专属的高级半透明蒙版底色与极细微光描边，完全消除未解析引用报错
    val isDark = isSystemInDarkTheme()
    val localBlurBackgroundColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
    val localBlurBorderColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.12f)
    val localBlurBorder = androidx.compose.foundation.BorderStroke(0.5.dp, localBlurBorderColor)

    // 使用自定义 Surface 替代 SuggestionChip，以获得更紧凑的间距且不带有额外的点击透明区域
    Surface(
        modifier = modifier
            .then(
                if (isBlur) {
                    Modifier
                        // 首先在 Modifier 链最前端裁剪 12.dp 圆角，杜绝毛玻璃直角溢出穿帮
                        .clip(RoundedCornerShape(12.dp))
                        // 挂载 Backdrop 模糊并应用高阶模糊效果
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(12.dp) },
                            effects = { blur(20f) }
                        )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp),
        border = if (isBlur) {
            // 为每一次改动添加详尽的中文注释：使用本地高级亮暗自适应的极细 0.5.dp 描边，展现更高级别的细节质感
            localBlurBorder
        } else {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = LocalContentColor.current.copy(alpha = 0.5f)
            )
        },
        color = if (isBlur) {
            // 为每一次改动添加详尽的中文注释：使用本地亮暗自适应的半透明蒙版底色，取代旧的全局模糊预设配置
            localBlurBackgroundColor
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                softWrap = false
            )
        }
    }
}
