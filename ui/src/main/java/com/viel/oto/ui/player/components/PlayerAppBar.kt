package com.viel.oto.ui.player.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.viel.oto.shared.R
import com.viel.oto.shared.model.AppSettings
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.BlurDropdownMenu
import com.viel.oto.ui.common.formatPeopleSubtitle
import com.viel.oto.ui.common.theme.OtoTheme
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAppBar(
    title: String,
    author: String,
    narrator: String,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onToggleProgressMode: (() -> Unit)? = null,
    isChapterProgressMode: Boolean = false,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState? = null,
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current
) {
    val navIcon = navigationIcon ?: Icons.Rounded.KeyboardArrowDown
    var showMenu by remember { mutableStateOf(false) }
    val unknownText = stringResource(R.string.common_unknown)
    val showProgressText = stringResource(
        if (isChapterProgressMode) R.string.player_show_total_progress else R.string.player_show_chapter_progress
    )

    TopAppBar(
        modifier = modifier, windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatPeopleSubtitle(author, narrator, fallback = unknownText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (contentColor == Color.White) {
                        Color.White.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    painter = rememberVectorPainter(navIcon),
                    contentDescription = stringResource(R.string.back_content_description),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = rememberVectorPainter(Icons.Rounded.MoreVert),
                        contentDescription = stringResource(R.string.more_content_description),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                BlurDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    hazeState = hazeState,
                    glassEffectMode = glassEffectMode
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(showProgressText)
                        },
                        onClick = {
                            onToggleProgressMode?.invoke()
                            showMenu = false
                        },
                        enabled = onToggleProgressMode != null
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = Color.Unspecified,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = Color.Unspecified,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Preview(apiLevel = 36)
@Composable
fun PlayerAppBarPreview() {
    OtoTheme {
        Surface(color = Color(0xFF1C1B1F)) {
            PlayerAppBar(
                title = "Preview Book",
                author = "Preview Author",
                narrator = "Preview Narrator",
                onNavigationClick = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                contentColor = Color.White
            )
        }
    }
}
