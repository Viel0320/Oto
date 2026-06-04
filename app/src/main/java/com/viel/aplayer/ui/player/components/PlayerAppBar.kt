package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

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
    onDeleteBook: (() -> Unit)? = null,
    isChapterProgressMode: Boolean = false,
    // Glass effect mode must be explicitly passed from the settings state by the player page; the player top bar no longer declares a Material default.
    glassEffectMode: GlassEffectMode,
    // The menu reuses the LayerBackdrop sampling source of the player background; it defaults to self-recovery during independent preview or when no parameters are passed.
    backdrop: LayerBackdrop? = null,
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current
) {
    val navIcon = navigationIcon ?: Icons.Rounded.KeyboardArrowDown
    var showMenu by remember { mutableStateOf(false) }
    // Safely create a local backdrop sampler internally to ensure an elegant high-definition blur background is still acquired when the external backdrop is null.
    val localBackdrop = backdrop ?: rememberLayerBackdrop()

    CenterAlignedTopAppBar(
        // Remove statusBarsPadding() from the modifier, using more professional windowInsets for direct adaptive management to completely eliminate double padding.
        modifier = modifier,
        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatPeopleSubtitle(author, narrator),
                    style = MaterialTheme.typography.labelMedium,
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
                    contentDescription = "Back",
                    tint = contentColor
                )
            }
        },
        actions = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = rememberVectorPainter(Icons.Rounded.MoreVert),
                        contentDescription = "More",
                        tint = contentColor
                    )
                }
                
                BlurDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    // Pass the LayerBackdrop sampled from the player Surface into the drop-down menu to generate the frosted glass effect.
                    backdrop = localBackdrop,
                    // The player's "more" menu switches between Material and miuix-blur depending on the selection in the settings page.
                    glassEffectMode = glassEffectMode
                ) {
                    // 1. Toggle progress mode
                    DropdownMenuItem(
                        text = {
                            Text(if (isChapterProgressMode) "Show Total Progress" else "Show Chapter Progress")
                        },
                        onClick = {
                            onToggleProgressMode?.invoke()
                            showMenu = false
                        },
                        enabled = onToggleProgressMode != null
                    )

                    // 2. Delete book
                    if (onDeleteBook != null) {
                        DropdownMenuItem(
                            text = { 
                                Text("Delete from Library", color = MaterialTheme.colorScheme.error) 
                            },
                            onClick = {
                                onDeleteBook.invoke()
                                showMenu = false
                            }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = Color.Unspecified,
            navigationIconContentColor = Color.Unspecified,
            titleContentColor = Color.Unspecified,
            actionIconContentColor = Color.Unspecified
        )
    )
}

@Preview(apiLevel = 36)
@Composable
fun PlayerAppBarPreview() {
    APlayerTheme {
        Surface(color = Color(0xFF1C1B1F)) {
            PlayerAppBar(
                title = "Preview Book",
                author = "Preview Author",
                narrator = "Preview Narrator",
                onNavigationClick = {},
                // The Preview explicitly references the default glass effect in the settings model, preventing PlayerAppBar parameters from having local default values again.
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                contentColor = Color.White
            )
        }
    }
}
