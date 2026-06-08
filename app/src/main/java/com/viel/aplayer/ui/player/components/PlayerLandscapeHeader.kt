package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState

// Landscape player header component.
//
// Decoupled to an independent file to display the book title, author information, and a "more options" menu (containing "toggle progress mode", "delete book", etc.).
// In landscape mode, to maximize immersion, this header floats directly on top of the background gradient without utilizing any extra background cards.
@Composable
fun PlayerLandscapeHeader(
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    glassEffectMode: GlassEffectMode,
    // Setup Haze State (Transition backdrop reference to HazeState)
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    // Localized Landscape Header Copy (Share player chrome resources with the portrait app bar)
    // Landscape mode renders its own menu, so it resolves the same progress/delete labels locally instead of duplicating English strings.
    val unknownText = stringResource(R.string.common_unknown)
    val unknownTitle = stringResource(R.string.common_unknown_title)
    val showProgressText = stringResource(
        if (settings.isChapterProgressMode) R.string.player_show_total_progress else R.string.player_show_chapter_progress
    )
    val deleteFromLibraryText = stringResource(R.string.player_delete_from_library)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = metadata.title.takeIf { it.isNotBlank() } ?: unknownTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = com.viel.aplayer.ui.common.formatPeopleSubtitle(metadata.author, metadata.narrator, fallback = unknownText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        var showLandscapeMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { showLandscapeMenu = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.more_content_description),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            BlurDropdownMenu(
                expanded = showLandscapeMenu,
                onDismissRequest = { showLandscapeMenu = false },
                hazeState = hazeState,
                glassEffectMode = glassEffectMode
            ) {
                DropdownMenuItem(
                    text = {
                        Text(showProgressText)
                    },
                    onClick = {
                        actions.content.onToggleProgressMode()
                        showLandscapeMenu = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(deleteFromLibraryText, color = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        actions.content.onDeleteBook()
                        showLandscapeMenu = false
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36, widthDp = 600)
@Composable
fun PlayerLandscapeHeaderPreview() {
    APlayerTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PlayerLandscapeHeader(
                metadata = BookMetadataState(title = "三体：黑暗森林", author = "刘慈欣", narrator = "王明"),
                settings = PlayerSettingsState(),
                actions = PlayerActions(),
                glassEffectMode = GlassEffectMode.Material,
                hazeState = null,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
