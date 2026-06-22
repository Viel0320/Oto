package com.viel.aplayer.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import dev.chrisbanes.haze.HazeState

/**
 * App-level "add library" source picker.
 *
 * Relocated out of the settings dialog host so the add-library flow no longer depends on settings.
 * Local SAF launches the system document-tree picker; WebDAV and ABS open the standalone
 * remote-connection overlay. The caller owns dismissal and routing.
 */
@Composable
fun AddLibrarySourceDialog(
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    onPickSaf: () -> Unit,
    onPickWebDav: () -> Unit,
    onPickAbs: () -> Unit,
    onDismiss: () -> Unit
) {
    APlayerDialogTemplate(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        title = {
            Text(
                text = stringResource(R.string.settings_add_library_type_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        body = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AddLibrarySourceRow(Icons.Rounded.FolderOpen, R.string.settings_library_type_local_saf, onPickSaf)
                AddLibrarySourceRow(Icons.Rounded.Cloud, R.string.settings_library_type_webdav, onPickWebDav)
                AddLibrarySourceRow(Icons.Rounded.Sync, R.string.settings_library_type_abs, onPickAbs)
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AddLibrarySourceRow(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(12.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
