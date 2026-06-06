package com.viel.aplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import dev.chrisbanes.haze.HazeState

/**
 * Material 3 Filter Chip (Homepage filter item supporting Material 3 standard and Haze custom liquid glass effects)
 *
 * Usage:
 * - By default, degrades gracefully to standard Material 3 [FilterChip] when glassEffectMode is Material.
 * - When glassEffectMode is Haze and a valid hazeState is provided, applies custom liquid glass refraction styling.
 */
@Composable
fun APlayerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Inject glass effect mode to toggle between standard and frosted glass styling
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Supply parent layout's HazeState for physical backdrop sampling
    hazeState: HazeState? = null
) {
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null

    if (isBlur) {
        // Rounded corner shape corresponding to Material 3 FilterChip default rounding specification (8.dp)
        val chipShape = RoundedCornerShape(8.dp)
        val isDark = com.viel.aplayer.ui.common.theme.LocalDarkTheme.current

        // Theme Aware Glass Selection Colors (Select distinct glass container and content colors based on active selection state)
        val containerColor = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        } else {
            if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)
        }

        val contentColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        // Liquid Glass Filter Chip (Introduce custom liquidGlassCompatEffect to the homepage filter chips under Haze mode) Apply fluid highlights and borders onto a custom Chip container using the RoundedCornerShape(8.dp).
        Box(
            modifier = modifier
                .clip(chipShape)
                .liquidGlassCompatEffect(
                    state = hazeState,
                    style = LiquidGlassStyle(shape = chipShape)
                )
                .background(containerColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                        tint = contentColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = contentColor
                )
            }
        }
    } else {
        // Fallback to standard Material 3 FilterChip implementation
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
}