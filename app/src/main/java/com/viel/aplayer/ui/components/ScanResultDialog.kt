package com.viel.aplayer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.ScanSessionEntity

@Composable
fun ScanResultDialog(
    session: ScanSessionEntity,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(text = "Scan Complete")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Library synchronization finished successfully.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(16.dp))
                
                ResultRow(
                    icon = Icons.Rounded.CheckCircle,
                    label = "New Books",
                    value = "${session.discoveredBookCount}",
                    color = MaterialTheme.colorScheme.primary
                )

                if (session.recoveredBookCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ResultRow(
                        icon = Icons.Rounded.CheckCircle,
                        label = "Recovered",
                        value = "${session.recoveredBookCount}",
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                ResultRow(
                    icon = Icons.Rounded.Warning,
                    label = "Missing Books",
                    value = "${session.unavailableBookCount}",
                    color = MaterialTheme.colorScheme.error
                )

                if (session.partialBookCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ResultRow(
                        icon = Icons.Rounded.Warning,
                        label = "Partly Lost",
                        value = "${session.partialBookCount}",
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Some files in these books are missing.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, top = 4.dp)
                    )
                }
                
                if (session.updatedBookCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ResultRow(
                        icon = Icons.Rounded.Sync,
                        label = "Updated Books",
                        value = "${session.updatedBookCount}",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                if (session.pendingActionCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ResultRow(
                        icon = Icons.Rounded.History,
                        label = "Skipped (Conflicts)",
                        value = "${session.pendingActionCount}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Conflicting or redundant items were skipped.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
private fun ResultRow(
    icon: ImageVector,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
