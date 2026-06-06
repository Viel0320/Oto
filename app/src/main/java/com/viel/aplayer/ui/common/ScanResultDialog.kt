package com.viel.aplayer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import org.json.JSONObject

@Composable
fun ScanResultDialog(
    session: ScanSessionEntity,
    // Scan Result Backdrop Source (Receive the page host's resolved dialog sampling state)
    // HomeDialogHost passes the app-level HazeState here so scan results use the same glass source as other Home dialogs.
    hazeState: HazeState? = null,
    // Scan Result Glass Mode (Respect the current app visual setting)
    // The concrete scan dialog stays derived from the common template and delegates blur-vs-material rendering to the shared shell.
    glassEffectMode: GlassEffectMode,
    onDismiss: () -> Unit
) {
    // The dialog now renders persisted counts from the completed ScanSession.
    val hasFailures = session.unavailableBookCount > 0
    val hasPending = session.pendingActionCount > 0
    val hasChanges = session.discoveredBookCount > 0 ||
        session.partialBookCount > 0 ||
        session.updatedBookCount > 0 ||
        hasFailures ||
        hasPending
    val summary = ScanSummaryItems.from(session.summaryJson)

    APlayerDialogTemplate(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        scrollable = true,
        headerAlignment = Alignment.CenterHorizontally,
        sectionSpacing = 0.dp,
        icon = {
            Icon(
                if (hasFailures || hasPending) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (hasFailures || hasPending) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Scan Complete",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        body = {
            // Scan Summary Body (Reuse the shared dialog rhythm while preserving scan result details)
            // The content remains local to the scan result dialog, but chrome, blur, padding, and actions are provided by APlayerDialogTemplate.
            Spacer(modifier = Modifier.height(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (hasChanges) {
                        "Library synchronization finished with the results below."
                    } else {
                        "Library synchronization finished. No new changes were found."
                    },
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
                ItemList(items = summary.newBooks)

                Spacer(modifier = Modifier.height(12.dp))

                ResultRow(
                    icon = Icons.Rounded.Warning,
                    label = "Scan Failures",
                    value = "${session.unavailableBookCount}",
                    color = MaterialTheme.colorScheme.error
                )
                ItemList(items = summary.failures)

                if (session.partialBookCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ResultRow(
                        icon = Icons.Rounded.Warning,
                        label = "Partial Imports",
                        value = "${session.partialBookCount}",
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Some candidate books need confirmation before importing incomplete file sets.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, top = 4.dp)
                    )
                    ItemList(items = summary.partialImports)
                }

                if (session.updatedBookCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ResultRow(
                        icon = Icons.Rounded.Sync,
                        label = "Updated Books",
                        value = "${session.updatedBookCount}",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    ItemList(items = summary.updatedBooks)
                }

                if (session.pendingActionCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ResultRow(
                        icon = Icons.Rounded.History,
                        label = "Needs Review",
                        value = "${session.pendingActionCount}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Conflicts, partial imports, or source updates are saved as pending actions.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, top = 4.dp)
                    )
                    ItemList(items = summary.pendingActions)
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
private fun ItemList(items: List<String>) {
    if (items.isEmpty()) return
    // Keep the dialog compact while still showing concrete scan results.
    Column(modifier = Modifier.padding(start = 36.dp, top = 6.dp)) {
        items.take(5).forEach { item ->
            Text(
                text = "- $item",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (items.size > 5) {
            Text(
                text = "+${items.size - 5} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultRow(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
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

private data class ScanSummaryItems(
    val newBooks: List<String> = emptyList(),
    val partialImports: List<String> = emptyList(),
    val updatedBooks: List<String> = emptyList(),
    val pendingActions: List<String> = emptyList(),
    val failures: List<String> = emptyList()
) {
    companion object {
        fun from(summaryJson: String): ScanSummaryItems {
            if (summaryJson.isBlank()) return ScanSummaryItems()
            return runCatching {
                val json = JSONObject(summaryJson)
                ScanSummaryItems(
                    newBooks = json.stringList("newBooks"),
                    partialImports = json.stringList("partialImports"),
                    updatedBooks = json.stringList("updatedBooks"),
                    pendingActions = json.stringList("pendingActions"),
                    failures = json.stringList("failures")
                )
            }.getOrDefault(ScanSummaryItems())
        }

        private fun JSONObject.stringList(name: String): List<String> {
            val array = optJSONArray(name) ?: return emptyList()
            return List(array.length()) { index -> array.optString(index) }
                .filter { it.isNotBlank() }
        }
    }
}
