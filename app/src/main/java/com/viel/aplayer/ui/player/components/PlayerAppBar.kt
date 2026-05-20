package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.theme.APlayerTheme

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
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current
) {
    val navIcon = navigationIcon ?: Icons.Rounded.KeyboardArrowDown
    var showMenu by remember { mutableStateOf(false) }

    CenterAlignedTopAppBar(
        modifier = modifier.statusBarsPadding(),
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
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    // 1. 进度模式切换
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

                    // 2. 删除书籍
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
                contentColor = Color.White
            )
        }
    }
}