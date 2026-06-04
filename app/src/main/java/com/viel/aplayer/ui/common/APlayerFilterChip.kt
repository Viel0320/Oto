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
 * Material 3 Filter Chip (Homepage filter item using native Material 3 FilterChip component)
 * Follows the standard styles defined by the Material 3 specification, featuring default border outlines, multi-state feedback, and focus colors.
 * When selected, a leading check icon is dynamically displayed with fade-in and slide-in transitions.
 */
@Composable
fun APlayerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Native FilterChip Styling (Render standard Material 3 FilterChip without custom borders or palettes)
    // This displays the official visual language and native interaction effects.
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