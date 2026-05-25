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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.settings.PlayerSettingsState
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

// 
// 抽离横屏播放器头部组件到独立文件。
// 负责展示书籍标题、作者信息以及包含“进度模式切换”、“删除书籍”等操作的更多菜单。
// 在横屏模式下，为了最大化沉浸感，该头部直接悬浮在背景渐变之上，不使用额外的背景卡片。
@Composable
fun PlayerLandscapeHeader(
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = metadata.title.takeIf { it.isNotBlank() } ?: "Unknown Title",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = com.viel.aplayer.ui.common.formatPeopleSubtitle(metadata.author, metadata.narrator),
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
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            BlurDropdownMenu(
                expanded = showLandscapeMenu,
                onDismissRequest = { showLandscapeMenu = false },
                backdrop = backdrop,
                glassEffectMode = glassEffectMode
            ) {
                DropdownMenuItem(
                    text = {
                        Text(if (settings.isChapterProgressMode) "Show Total Progress" else "Show Chapter Progress")
                    },
                    onClick = {
                        actions.content.onToggleProgressMode()
                        showLandscapeMenu = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text("Delete from Library", color = MaterialTheme.colorScheme.error)
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
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
