package com.viel.oto.ui.player.components

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
import com.viel.oto.shared.R
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.BlurDropdownMenu
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.player.BookMetadataState
import com.viel.oto.ui.player.PlayerActions
import com.viel.oto.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState

@Composable
fun PlayerLandscapeHeader(
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    val unknownText = stringResource(R.string.common_unknown)
    val unknownTitle = stringResource(R.string.common_unknown_title)
    val showProgressText = stringResource(
        if (settings.isChapterProgressMode) R.string.player_show_total_progress else R.string.player_show_chapter_progress
    )

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
                text = com.viel.oto.ui.common.formatPeopleSubtitle(metadata.author, metadata.narrator, fallback = unknownText),
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
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36, widthDp = 600)
@Composable
fun PlayerLandscapeHeaderPreview() {
    OtoTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PlayerLandscapeHeader(
                metadata = BookMetadataState(title = "The Dark Forest", author = "Cixin Liu", narrator = "Narrator"),
                settings = PlayerSettingsState(),
                actions = PlayerActions(),
                glassEffectMode = GlassEffectMode.Material,
                hazeState = null,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
