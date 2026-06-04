package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDialog
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import dev.chrisbanes.haze.HazeState

/**
 * AudiobookActionDialogs Setup (Long-press Audiobook Action Dialogs)
 *
 * Independently encapsulated Audiobook action dialog component group.
 * Packs and encapsulates the first-level management panel Dialog and the second-level soft delete confirmation Dialog,
 * isolating visibility logic inside and slimming down the main HomeScreenState.kt file.
 *
 * Migration details (miuix-blur frosted glass):
 * Replaces window-level blurBehindRadius with miuix-blur rendering inside [BlurDialog].
 * Callers pass the shared [LayerBackdrop] to make the Dialog panel sample home page content directly, forming a clear, high-density frosted glass effect.
 */
@Composable
fun AudiobookActionDialogs(
    modifier: Modifier = Modifier,
    bookWithProgress: BookWithProgress?,
    // Setup Haze State (Transition backdrop reference to HazeState)
    hazeState: HazeState? = null,
    // Glass effect mode must be explicitly passed from the settings state by the host page, avoiding default values inside the dialog wrapper.
    glassEffectMode: GlassEffectMode,
    onDismissRequest: () -> Unit,
    onUpdateReadStatus: (String, String) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit
) {
    if (bookWithProgress == null) return

    val book = bookWithProgress.book
    // Internally maintain visibility state of the second-level soft delete confirmation Dialog
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ─────────────────────────────────────────────────────────────────────────
    // First-Level Management Dialog (Miuix-Blur Frosted Glass Effect)
    // Uses BlurDialog + miuix-blur to implement frosted glass effect sampling home page content.
    // ─────────────────────────────────────────────────────────────────────────
    if (!showDeleteConfirm) {
        BlurDialog(
            onDismissRequest = onDismissRequest,
            // Pass the shared HazeState of the home page to ensure the first-level operations panel can sample current bookshelf background.
            hazeState = hazeState,
            // Pass user settings to BlurDialog; Material mode will skip internal miuix-blur related textureBlur modifiers.
            glassEffectMode = glassEffectMode,
            // Blur radius, background color, and tint are adaptively configured inside BlurDialog, no longer passed from the call point.
            scrollable = true
        ) {
            // Dialog main content area, using Column to vertically align functional blocks
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Icon area, displaying the Tune management icon centered
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Book title, centered and bolded, up to two lines, ellipsis on overflow
                Text(
                    text = book.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                // Author/narrator subtitle (if any), centered and light-colored
                if (book.author.isNotBlank() || book.narrator.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatPeopleSubtitle(book.author, book.narrator),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Subtle divider line with 0.5f opacity to reduce visual weight
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ─────────────────────────────────────────────────────────────
                // 1. Reading Status Marking (Mark Reading Status Chips)
                // Displays three side-by-side FilterChips, corresponding to "Not Started", "In Progress", and "Finished" statuses.
                // ─────────────────────────────────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "标记阅读状态",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val statusList = listOf(
                            AudiobookSchema.ReadStatus.NOT_STARTED to "未开始",
                            AudiobookSchema.ReadStatus.IN_PROGRESS to "进行中",
                            AudiobookSchema.ReadStatus.FINISHED to "已完成"
                        )
                        statusList.forEach { (status, label) ->
                            val isSelected = book.readStatus == status
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    onUpdateReadStatus(book.id, status)
                                    onDismissRequest()
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                // weight(1f) divides row width equally among three Chips
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Subtle divider line
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // ─────────────────────────────────────────────────────────────
                // 2. Regenerate Cover and Metadata (Trigger Metadata Re-scan)
                // Provides a ripple-clickable surface to force background media re-scan.
                // ─────────────────────────────────────────────────────────────
                Surface(
                    onClick = {
                        onForceRegenerate(book.id)
                        onDismissRequest()
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Column {
                            Text(
                                text = "重建封面与元数据",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "强制从音频文件中重新提取封面和描述信息",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // 3. Remove from Library (Red Area Soft Delete Alert Card)
                // Warning card for soft deletion from library.
                // ─────────────────────────────────────────────────────────────
                Surface(
                    onClick = {
                        showDeleteConfirm = true
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                text = "从媒体库移除",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "仅从播放列表中移出此书籍，手机源文件仍保留",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom "Cancel" button, right-aligned
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("取消")
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Second-Level Delete Confirm Dialog (Miuix-Blur Warning Dialog)
    // Secondary soft delete confirmation Dialog (also uses BlurDialog + miuix-blur with slightly deeper blur to reinforce warning).
    // ─────────────────────────────────────────────────────────────────────────
    if (showDeleteConfirm) {
        BlurDialog(
            onDismissRequest = { showDeleteConfirm = false },
            // Secondary confirmation panel reuses the home page shared backdrop, keeping background sampling source consistent with first-level panel.
            hazeState = hazeState,
            // Delete confirmation Dialog follows user-selected glass effect mode.
            glassEffectMode = glassEffectMode,
            // Delete confirmation Dialog config is also handled by BlurDialog, avoiding hard-coded secondary blur parameters.
            scrollable = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Delete confirmation icon, centered, using error tint to warn user
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Confirmation Dialog title
                Text(
                    text = "确认从媒体库移除？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Soft delete explanation text, reminding user that it only removes from playlist without deleting physical files
                Text(
                    text = "您确定要从 APlayer 媒体库中移除《${book.title}》吗？\n\n⚠️ 注意：此操作仅为软删除，将从播放列表中移出，但不会删除您手机存储中的物理音频文件。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Confirm/Cancel button row, right-aligned layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = { showDeleteConfirm = false }
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            onDeleteBook(book.id)
                            showDeleteConfirm = false
                            onDismissRequest()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("确认删除")
                    }
                }
            }
        }
    }
}
