package com.viel.oto.ui.common

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
import com.viel.oto.shared.R
import com.viel.oto.ui.common.theme.LocalHazeState
import com.viel.oto.ui.common.theme.LocalIsBlur
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Custom homepage filter item with Material-like sizing and Haze support.
 *
 * Recreates the Home filter affordance without delegating to Material3 FilterChip, while preserving
 * the Material-like 32.dp visual container and the separate 48.dp accessibility touch target.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun OtoFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val isBlur = LocalIsBlur.current && (hazeState ?: LocalHazeState.current) != null
    val chipShape = MaterialTheme.shapes.small
    val colors = filterChipColors(selected = selected, isBlur = isBlur)
    val interactionSource = remember { MutableInteractionSource() }
    val choiceStateDescription = stringResource(
        if (selected) {
            R.string.accessibility_choice_selected
        } else {
            R.string.accessibility_choice_unselected
        }
    )
    val startPadding = if (selected) {
        OtoFilterChipLeadingContentStartPadding
    } else {
        OtoFilterChipLabelHorizontalPadding
    }
    val visualModifier = if (isBlur) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeMaterials.ultraThin()
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = OtoFilterChipMinimumTouchTarget,
                minHeight = OtoFilterChipMinimumTouchTarget
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
                .defaultMinSize(minWidth = OtoFilterChipMinimumVisualWidth)
                .height(OtoFilterChipVisualHeight)
                .clip(chipShape)
                .then(visualModifier)
                .background(color = colors.containerColor, shape = chipShape)
                .then(
                    if (colors.borderColor == Color.Transparent) {
                        Modifier
                    } else {
                        Modifier.border(
                            width = OtoFilterChipOutlineWidth,
                            color = colors.borderColor,
                            shape = chipShape
                        )
                    }
                )
                .padding(
                    start = startPadding,
                    end = OtoFilterChipLabelHorizontalPadding
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
                        modifier = Modifier.size(OtoFilterChipLeadingIconSize),
                        tint = colors.contentColor
                    )
                    Spacer(modifier = Modifier.width(OtoFilterChipIconLabelSpacing))
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

private val OtoFilterChipVisualHeight = 32.dp
private val OtoFilterChipMinimumVisualWidth = 48.dp
private val OtoFilterChipMinimumTouchTarget = 48.dp
private val OtoFilterChipOutlineWidth = 1.dp
private val OtoFilterChipLeadingIconSize = 18.dp
private val OtoFilterChipIconLabelSpacing = 8.dp
private val OtoFilterChipLabelHorizontalPadding = 16.dp
private val OtoFilterChipLeadingContentStartPadding = 8.dp

private data class OtoFilterChipColors(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color
)

@Composable
private fun filterChipColors(
    selected: Boolean,
    isBlur: Boolean
): OtoFilterChipColors {
    val isDark = com.viel.oto.ui.common.theme.LocalDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme

    return if (isBlur) {
        OtoFilterChipColors(
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
        OtoFilterChipColors(
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
