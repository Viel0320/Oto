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
    hazeState: HazeState? = null
) {
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    val noChaptersText = stringResource(R.string.player_no_chapters)
    val addBookmarkContentDescription = stringResource(R.string.bookmark_add_title)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBlur) {
            val chipShape = RoundedCornerShape(12.dp)
            val isDark = com.viel.aplayer.ui.common.theme.LocalDarkTheme.current

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
                    .clip(chipShape)
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
            modifier = Modifier.padding(start = 16.dp)
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
