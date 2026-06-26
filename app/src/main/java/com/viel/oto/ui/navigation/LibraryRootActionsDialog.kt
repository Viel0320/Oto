package com.viel.oto.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.application.library.settings.SettingsRootItem
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.OtoDialogTemplate
import dev.chrisbanes.haze.HazeState

/**
 * App-level actions menu for a library root (edit / sync or rescan / delete).
 *
 * Relocated out of the settings dialog host so root management no longer depends on settings. Edit
 * opens the standalone remote-connection overlay (or the SAF relocate picker); delete asks the caller
 * to raise [DeleteLibraryRootDialog]. The caller owns dismissal and routes actions to the view model.
 */
@Composable
fun LibraryRootActionsDialog(
    root: SettingsRootItem,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    onEdit: (SettingsRootItem) -> Unit,
    onSync: (SettingsRootItem) -> Unit,
    onRescan: (SettingsRootItem) -> Unit,
    onRequestDelete: (SettingsRootItem) -> Unit,
    onDismiss: () -> Unit
) {
    OtoDialogTemplate(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        title = {
            Text(
                text = root.displayName.ifBlank { stringResource(R.string.settings_root_action_title_fallback) },
                style = MaterialTheme.typography.titleLarge
            )
        },
        body = {
            Column(modifier = Modifier.fillMaxWidth()) {
                RootActionRow(
                    icon = Icons.Rounded.Info,
                    label = if (root.isSafRoot) {
                        R.string.settings_root_action_relocate_saf
                    } else {
                        R.string.settings_root_action_edit_remote
                    },
                    onClick = {
                        onDismiss()
                        onEdit(root)
                    }
                )
                RootActionRow(
                    icon = Icons.Rounded.Refresh,
                    label = if (root.isAbsRoot) {
                        R.string.settings_root_action_sync
                    } else {
                        R.string.settings_root_action_rescan
                    },
                    onClick = {
                        onDismiss()
                        if (root.isAbsRoot) onSync(root) else onRescan(root)
                    }
                )
                RootActionRow(
                    icon = Icons.Rounded.Delete,
                    label = R.string.settings_root_action_remove,
                    destructive = true,
                    onClick = { onRequestDelete(root) }
                )
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Confirmation for removing a library root, raised by [LibraryRootActionsDialog]'s delete action.
 */
@Composable
fun DeleteLibraryRootDialog(
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    OtoDialogTemplate(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        title = {
            Text(
                text = stringResource(R.string.settings_delete_root_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        body = { Text(stringResource(R.string.settings_delete_root_body)) },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.settings_delete_root_confirm))
            }
        }
    )
}

@Composable
private fun RootActionRow(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
        )
    }
}
