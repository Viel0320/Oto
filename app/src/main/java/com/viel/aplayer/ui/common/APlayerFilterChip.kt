package com.viel.aplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import dev.chrisbanes.haze.HazeState

/**
 * Home Filter Chip (Custom homepage filter item with Material-like sizing and Haze support)
 *
 * Recreates the Home filter affordance without delegating to Material3 FilterChip, while preserving
 * the Material-like 32.dp visual container and the separate 48.dp accessibility touch target.
 */
@Composable
fun APlayerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Glass Mode Input (Keep visual-effect selection outside the chip renderer)
    // The caller owns user settings, while this component only maps the mode into Material-like or Haze colors.
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Haze Sampling Input (Provide backdrop state only when glass rendering is active)
    // A null state deliberately falls back to the non-glass palette so previews and tests stay deterministic.
    hazeState: HazeState? = null
) {
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    // Filter Chip Shape Token (Match the Material 3 filter chip corner size)
    // Home keeps the familiar small rounded rectangle while owning the drawing and semantics locally.
    val chipShape = RoundedCornerShape(APlayerFilterChipCornerRadius)
    val colors = aPlayerFilterChipColors(selected = selected, isBlur = isBlur)
    // Filter Chip Press Feedback Source (Keep press state private while suppressing the default ripple)
    // Home filter chips already communicate selection through fill, border, icon, and accessibility state, so the extra background press indication is intentionally disabled.
    val interactionSource = remember { MutableInteractionSource() }
    val choiceStateDescription = stringResource(
        if (selected) {
            R.string.accessibility_choice_selected
        } else {
            R.string.accessibility_choice_unselected
        }
    )
    val startPadding = if (selected) {
        APlayerFilterChipLeadingContentStartPadding
    } else {
        APlayerFilterChipLabelHorizontalPadding
    }
    val visualModifier = if (isBlur) {
        // Liquid Glass Visual Layer (Apply Haze only to the visible 32.dp chip body)
        // The outer selectable node remains 48.dp for touch and accessibility, while the glass sample stays clipped to the Material-like container.
        Modifier.liquidGlassCompatEffect(
            state = hazeState,
            style = LiquidGlassStyle(shape = chipShape)
        )
    } else {
        Modifier
    }

    // Filter Chip Accessibility Shell (Separate minimum interactive size from visual chip height)
    // Compose's official FilterChip normally provides this split internally; the custom implementation keeps it explicit so both Material and Haze modes behave the same.
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = APlayerFilterChipMinimumTouchTarget,
                minHeight = APlayerFilterChipMinimumTouchTarget
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics {
                stateDescription = choiceStateDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                // Filter Chip Visual Container (Keep the rendered chip close to Material 3 FilterChip dimensions)
                // The container has its own minimum width and fixed height so labels, selected icons, and touch padding cannot resize the row unexpectedly.
                .defaultMinSize(minWidth = APlayerFilterChipMinimumVisualWidth)
                .height(APlayerFilterChipVisualHeight)
                .clip(chipShape)
                .then(visualModifier)
                .background(color = colors.containerColor, shape = chipShape)
                .then(
                    if (colors.borderColor == Color.Transparent) {
                        Modifier
                    } else {
                        // Filter Chip Outline (Recreate the inactive Material outline without using FilterChipDefaults)
                        // A one-pixel border keeps inactive choices discoverable in Material mode and softly framed in Haze mode.
                        Modifier.border(
                            width = APlayerFilterChipOutlineWidth,
                            color = colors.borderColor,
                            shape = chipShape
                        )
                    }
                )
                .padding(
                    start = startPadding,
                    end = APlayerFilterChipLabelHorizontalPadding
                ),
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
                        modifier = Modifier.size(APlayerFilterChipLeadingIconSize),
                        tint = colors.contentColor
                    )
                    Spacer(modifier = Modifier.width(APlayerFilterChipIconLabelSpacing))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = colors.contentColor
                )
            }
        }
    }
}

// Filter Chip Dimension Tokens (Mirror Material-style visuals while keeping Android touch guidance)
// These values intentionally separate the visible 32.dp chip from the 48.dp semantics target required for reliable touch and assistive input.
private val APlayerFilterChipVisualHeight = 32.dp
private val APlayerFilterChipMinimumVisualWidth = 48.dp
private val APlayerFilterChipMinimumTouchTarget = 48.dp
private val APlayerFilterChipCornerRadius = 8.dp
private val APlayerFilterChipOutlineWidth = 1.dp
private val APlayerFilterChipLeadingIconSize = 18.dp
private val APlayerFilterChipIconLabelSpacing = 8.dp
private val APlayerFilterChipLabelHorizontalPadding = 16.dp
private val APlayerFilterChipLeadingContentStartPadding = 8.dp

// Filter Chip Palette (Own Material and Haze color decisions behind one rendering path)
// The chip no longer branches into official and custom components, so color selection is the only mode-specific behavior left.
private data class APlayerFilterChipColors(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color
)

@Composable
private fun aPlayerFilterChipColors(
    selected: Boolean,
    isBlur: Boolean
): APlayerFilterChipColors {
    val isDark = com.viel.aplayer.ui.common.theme.LocalDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme

    return if (isBlur) {
        // Haze Chip Palette (Preserve the liquid-glass tint language without changing chip semantics)
        // Selected chips receive the brand accent wash, while inactive chips stay translucent enough to sample the bookshelf backdrop.
        APlayerFilterChipColors(
            containerColor = if (selected) {
                colorScheme.primary.copy(alpha = 0.25f)
            } else {
                if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)
            },
            contentColor = if (selected) {
                colorScheme.primary
            } else {
                colorScheme.onSurfaceVariant
            },
            borderColor = if (selected) {
                Color.Transparent
            } else {
                colorScheme.outlineVariant.copy(alpha = 0.72f)
            }
        )
    } else {
        // Material Chip Palette (Approximate Material 3 FilterChip defaults without calling the official chip)
        // The selected state uses secondaryContainer/onSecondaryContainer and inactive chips retain a transparent container with an outline.
        APlayerFilterChipColors(
            containerColor = if (selected) {
                colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            contentColor = if (selected) {
                colorScheme.onSecondaryContainer
            } else {
                colorScheme.onSurfaceVariant
            },
            borderColor = if (selected) {
                Color.Transparent
            } else {
                colorScheme.outline
            }
        )
    }
}
