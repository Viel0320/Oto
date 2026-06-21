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
import com.viel.aplayer.shared.settings.GlassEffectMode
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
fun APlayerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    hazeState: HazeState? = null
) {
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    val chipShape = RoundedCornerShape(APlayerFilterChipCornerRadius)
    val colors = aPlayerFilterChipColors(selected = selected, isBlur = isBlur)
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
                .defaultMinSize(minWidth = APlayerFilterChipMinimumVisualWidth)
                .height(APlayerFilterChipVisualHeight)
                .clip(chipShape)
                .then(visualModifier)
                .background(color = colors.containerColor, shape = chipShape)
                .then(
                    if (colors.borderColor == Color.Transparent) {
                        Modifier
                    } else {
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

private val APlayerFilterChipVisualHeight = 32.dp
private val APlayerFilterChipMinimumVisualWidth = 48.dp
private val APlayerFilterChipMinimumTouchTarget = 48.dp
private val APlayerFilterChipCornerRadius = 8.dp
private val APlayerFilterChipOutlineWidth = 1.dp
private val APlayerFilterChipLeadingIconSize = 18.dp
private val APlayerFilterChipIconLabelSpacing = 8.dp
private val APlayerFilterChipLabelHorizontalPadding = 16.dp
private val APlayerFilterChipLeadingContentStartPadding = 8.dp

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
