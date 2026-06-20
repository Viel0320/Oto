package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ChapterDisplay(
    currentChapterTitle: String?,
    onChapterClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Setup Haze State (Transition backdrop reference to HazeState)
    hazeState: HazeState? = null
) {
    // Resolve backdrop configurations (To optimize performance under Haze styles)
    // Avoids redundant blur computations by using pure mask overlays.
    // Determine Glass Blur Status (Enable blur only if in Haze mode and state is provided)
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    // Localized Chapter Fallback Copy (Use resources for empty chapter and bookmark accessibility text)
    // Chapter titles usually come from media metadata, but the empty-state fallback and action label are app-authored UI copy.
    val noChaptersText = stringResource(R.string.player_no_chapters)
    val addBookmarkContentDescription = stringResource(R.string.bookmark_add_title)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBlur) {
            // Render custom glassmorphism pill (To achieve homocentric layouts and precise roundings)
            // Wraps items inside basic Box container with chained clips, backgrounds, clickables, and borders.
            val chipShape = RoundedCornerShape(12.dp)
            // Query active theme properties (To load adaptive styling boundaries based on dark/light theme state) Read theme preference.
            val isDark = com.viel.aplayer.ui.common.theme.LocalDarkTheme.current
            
            // 1. Shaded mask brush (To enforce proper contrast ratios across active theme modes)
            val maskBrush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f))
                } else {
                    listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.35f))
                }
            )
            
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    // Setup Suggestion Chip Haze (Apply hazeChild to the chip container Box)
                    // Clip Chip Shape (Apply clip to chip container) Pre-clip container shape before applying hazeChild to avoid rendering shape mismatch.
                    .clip(chipShape)
                    // Chapter Chip Haze Layer (Apply the direct Haze material inside the clipped chip bounds)
                    .hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.ultraThin()
                    )
                    .background(maskBrush)
                    .clickable(onClick = onChapterClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Theme Conforming Colors (Use onSurface and onSurfaceVariant colors instead of primary or contentColor to follow standard Material styling)
                    // Removing custom primary tinting ensures the chip text and icon look natural and maintain appropriate contrast ratios.
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentChapterTitle ?: title ?: noChaptersText,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // Material fallback layout (To maintain standard Material 3 SuggestionChip styling)
            // Default Suggestion Chip Styling (Remove custom labelColor, iconContentColor, and borderColor overrides to prevent overriding with contentColor)
            // Letting SuggestionChip use standard theme properties helps preserve dynamic colors across layout hierarchies.
            SuggestionChip(
                onClick = onChapterClick,
                modifier = Modifier.weight(1f, fill = false),
                label = {
                    Text(
                        text = currentChapterTitle ?: title ?: noChaptersText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                icon = {
                    Icon(
                        Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        modifier = Modifier.size(SuggestionChipDefaults.IconSize)
                    )
                },
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        IconButton(
            onClick = onBookmarkClick,
            modifier = Modifier.padding(start = 16.dp) // Offset bookmark button (To prevent buttons overlapping chip bounds)
        ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = addBookmarkContentDescription)
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun ChapterDisplayPreview() {
    APlayerTheme {
        Surface {
            ChapterDisplay(
                currentChapterTitle = "Chapter 1: The Beginning",
                title = "The Great Adventure",
                onChapterClick = {},
                onBookmarkClick = {}
            )
        }
    }
}
