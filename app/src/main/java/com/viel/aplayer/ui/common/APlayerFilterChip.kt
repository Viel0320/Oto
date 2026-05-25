package com.viel.aplayer.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * 使用 Android Material 3 原生 FilterChip 实现的首页筛选过滤组件。
 * 完全回归 Material 3 官方规范定义的标准原生样式，具备原生自带的优雅描边、多态反馈与焦点色调。
 * 在选中状态下，组件前部会自动带入具有滑入/淡入过渡动画的勾选图标（Check Icon）。
 */
@Composable
fun APlayerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 使用 Material 3 默认自带的原生 FilterChip，去除了任何自定义的配色与描边，
    // 呈现最纯净的官方视觉和交互动效。
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else null,
        modifier = modifier
    )
}