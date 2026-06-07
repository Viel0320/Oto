package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import dev.chrisbanes.haze.HazeState

/**
 * AudiobookActionDialogs Setup (Derived Home long-press dialogs)
 *
 * Derives the Home audiobook action menu and its delete confirmation dialog from the shared dialog template.
 * The parent HomeDialogHost owns the page dialog state, while this component only owns the nested confirmation step inside the active audiobook flow.
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
    // First-Level Management Dialog (Haze Frosted Glass Effect)
    // Uses BlurDialog + Haze to implement frosted glass effect sampling home page content.
    // ─────────────────────────────────────────────────────────────────────────
    if (!showDeleteConfirm) {
        APlayerDialogTemplate(
            onDismissRequest = onDismissRequest,
            // Pass the shared HazeState of the home page to ensure the first-level operations panel can sample current bookshelf background.
            hazeState = hazeState,
            // Pass user settings to BlurDialog; Material mode will skip internal Haze related textureBlur modifiers.
            glassEffectMode = glassEffectMode,
            // Blur radius, background color, and tint are adaptively configured inside BlurDialog, no longer passed from the call point.
            scrollable = true,
            headerAlignment = Alignment.CenterHorizontally,
            sectionSpacing = 0.dp,
            title = {
                // Action Dialog Identity Row (Place cover beside title metadata)
                // The selected cover sits left of the title and creator text so the long-press menu reads as one compact item identity block.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AudiobookActionCover(
                        coverPath = CoverImageSourceSelector.small(
                            thumbnailPath = book.thumbnailPath,
                            coverPath = book.coverPath
                        ),
                        coverLastUpdated = book.lastScannedAt,
                        modifier = Modifier.size(64.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        // Action Dialog Title Clamp (Keep selected audiobook title on one stable line)
                        // Prevents long book names from expanding the long-press menu header and pushing the action controls downward.
                        Text(
                            text = book.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )

                        // Author/narrator subtitle (if any), aligned with the title and light-colored.
                        if (book.author.isNotBlank() || book.narrator.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatPeopleSubtitle(book.author, book.narrator),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // Action Dialog Subtitle Clamp (Keep author and narrator metadata on one stable line)
                                // Long creator metadata is truncated instead of wrapping so the menu remains compact and predictable.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            },
            body = {

                Spacer(modifier = Modifier.height(16.dp))

                // Subtle divider line with 0.5f opacity to reduce visual weight
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ─────────────────────────────────────────────────────────────
                // 1. Reading Status Marking (Mark Reading Status Chips)
                // Displays three side-by-side outline/fill chips, corresponding to "Not Started", "In Progress", and "Finished" statuses.
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
                        // Read Status Color Mapping (Bind each status to a restrained semantic accent)
                        // These softer tones keep green/blue/purple meaning while avoiding high-saturation button blocks inside the glass dialog.
                        val statusList = listOf(
                            ReadStatusChipSpec(
                                status = AudiobookSchema.ReadStatus.NOT_STARTED,
                                label = "未开始",
                                accentColor = Color(0xFF72D38A)
                            ),
                            ReadStatusChipSpec(
                                status = AudiobookSchema.ReadStatus.IN_PROGRESS,
                                label = "进行中",
                                accentColor = Color(0xFF7DB7FF)
                            ),
                            ReadStatusChipSpec(
                                status = AudiobookSchema.ReadStatus.FINISHED,
                                label = "已完成",
                                accentColor = Color(0xFFD49AEF)
                            )
                        )
                        statusList.forEach { spec ->
                            ReadStatusChip(
                                label = spec.label,
                                accentColor = spec.accentColor,
                                selected = book.readStatus == spec.status,
                                onClick = {
                                    onUpdateReadStatus(book.id, spec.status)
                                    onDismissRequest()
                                },
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
            },
            actions = {
                // Cancel Action (Close the action menu without mutating the selected audiobook)
                // Keeps command placement inside the shared template action row while the actual dismissal remains a page-owned callback.
                TextButton(onClick = onDismissRequest) {
                    Text("取消")
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Second-Level Delete Confirm Dialog (Haze Warning Dialog)
    // Secondary soft delete confirmation Dialog (also uses BlurDialog + Haze with slightly deeper blur to reinforce warning).
    // ─────────────────────────────────────────────────────────────────────────
    if (showDeleteConfirm) {
        APlayerDialogTemplate(
            onDismissRequest = { showDeleteConfirm = false },
            // Secondary confirmation panel reuses the home page shared backdrop, keeping background sampling source consistent with first-level panel.
            hazeState = hazeState,
            // Delete confirmation Dialog follows user-selected glass effect mode.
            glassEffectMode = glassEffectMode,
            // Delete confirmation Dialog config is also handled by BlurDialog, avoiding hard-coded secondary blur parameters.
            scrollable = false,
            headerAlignment = Alignment.CenterHorizontally,
            sectionSpacing = 0.dp,
            icon = {
                // Delete confirmation icon, centered, using error tint to warn user
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(28.dp)
                )
            },
            title = {
                Spacer(modifier = Modifier.height(12.dp))

                // Confirmation Dialog title
                Text(
                    text = "确认从媒体库移除？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            body = {
                Spacer(modifier = Modifier.height(12.dp))

                // Soft delete explanation text, reminding user that it only removes from playlist without deleting physical files
                Text(
                    text = "您确定要从 APlayer 媒体库中移除《${book.title}》吗？\n\n⚠️ 注意：此操作仅为软删除，将从播放列表中移出，但不会删除您手机存储中的物理音频文件。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(20.dp))
            },
            actions = {
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
        )
    }
}

// Read Status Chip Spec (Pairs each read-status command with its label and semantic accent)
// Keeps color mapping data outside the composable rendering branch so chip drawing remains small and predictable.
private data class ReadStatusChipSpec(
    val status: String,
    val label: String,
    val accentColor: Color
)

@Composable
private fun ReadStatusChip(
    label: String,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        accentColor.copy(alpha = 0.18f)
    } else {
        Color.Transparent
    }
    val borderColor = accentColor.copy(alpha = if (selected) 0.82f else 0.42f)
    val textColor = if (selected) {
        accentColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(14.dp),
        // Read Status Chip Tonal Fill (Replace saturated button blocks with a quiet selected wash)
        // The selected state still receives a semantic fill, but low alpha keeps it aligned with the glass dialog surface.
        color = containerColor,
        // Read Status Chip Hairline Border (Keep status color without heavy visual weight)
        // A softer border preserves the requested green/blue/purple affordance while reducing the boxed-button feeling.
        border = BorderStroke(0.8.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Read Status Accent Dot (Use a small selection cue instead of a loud full button fill)
            // The dot makes the active state scannable while keeping each chip visually lightweight.
            Surface(
                modifier = Modifier.size(if (selected) 7.dp else 5.dp),
                shape = CircleShape,
                color = accentColor.copy(alpha = if (selected) 1f else 0.5f)
            ) {}
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AudiobookActionCover(
    coverPath: String?,
    coverLastUpdated: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Action Dialog Cover Loading (Use the same small thumbnail contract as listgroup covers)
            // The long-press menu only needs a compact identity cue, so ThumbnailSmall avoids decoding larger artwork.
            var isImageError by remember(coverPath) { mutableStateOf(false) }
            if (coverPath != null && !isImageError) {
                val context = LocalContext.current
                val request = remember(coverPath, coverLastUpdated) {
                    CoverImageRequestFactory.build(
                        context = context,
                        sourcePath = coverPath,
                        lastUpdated = coverLastUpdated,
                        variant = CoverImageVariant.ThumbnailSmall,
                        scene = "home-action-dialog-cover"
                    )
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = {
                        // Cover Fallback State (Degrade to the local placeholder when the thumbnail cannot be loaded)
                        // Keeps the dialog header stable without probing disk synchronously from composition.
                        isImageError = true
                    }
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
