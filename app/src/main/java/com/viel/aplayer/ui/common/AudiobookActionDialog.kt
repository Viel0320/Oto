package com.viel.aplayer.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.shared.settings.GlassEffectMode
// Import AppWindowSizeClass: Use standardized layout provider to get the current window size details.
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import dev.chrisbanes.haze.HazeState

/**
 * Audiobook Action Dialog Book (Reusable payload for audiobook action menus)
 *
 * Carries only the identity, creator, cover, and read-status fields needed by the shared action dialog so callers can adapt scene-specific book projections without leaking their own models into common UI.
 */
data class AudiobookActionDialogBook(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val coverPath: String?,
    val thumbnailPath: String?,
    val lastScannedAt: Long,
    // Read Status Type Safe: Change readStatus projection field type to ReadStatus enum for type safety.
    val readStatus: AudiobookSchema.ReadStatus?
)

/**
 * Audiobook Action Dialog (Reusable audiobook long-press menu)
 *
 * Derives the audiobook action menu and its delete confirmation dialog from the shared dialog template.
 * The host owns page dialog state and business callbacks, while this component owns only rendering and the nested confirmation step inside the active audiobook flow.
 */
@Composable
fun AudiobookActionDialog(
    book: AudiobookActionDialogBook?,
    // Dialog Backdrop Source (Transition backdrop reference to HazeState)
    // Callers pass their own sampling source so the shared dialog can render inside Home, Detail, or future library surfaces without knowing page layout.
    hazeState: HazeState? = null,
    // Glass effect mode must be explicitly passed from the settings state by the host page, avoiding default values inside the dialog wrapper.
    glassEffectMode: GlassEffectMode,
    // Cover Request Scene (Preserve caller-level diagnostics in shared image loading)
    // The common dialog builds the Coil request, while each host can keep cache logs attributed to the screen that opened the menu.
    coverRequestScene: String = "audiobook-action-dialog-cover",
    onDismissRequest: () -> Unit,
    // Edit Book Command (Optional host-owned metadata editing entry)
    // The shared dialog only emits the selected book id; hosts decide whether that id opens EditBookRoute or no edit action should be shown.
    onEditBook: ((String) -> Unit)? = null,
    // Update Read Status Callback: Change readStatus parameter to ReadStatus enum.
    onUpdateReadStatus: (String, AudiobookSchema.ReadStatus) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit
) {
    if (book == null) return

    // Internally maintain visibility state of the second-level soft delete confirmation Dialog
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Action Dialog Orientation Gate (Switch only when the active window is landscape)
    // Non-portrait windows usually have scarce vertical height, so the first-level action menu uses a two-column composition while portrait keeps the familiar stacked flow.
    // Resolve Window Layout: Retrieve current viewport properties via standardized AppWindowSizeClass provider to configure the layout orientation.
    val useLandscapeActionLayout = LocalAppWindowSizeClass.current.isLandscape

    // ─────────────────────────────────────────────────────────────────────────
    // First-Level Management Dialog (Haze Frosted Glass Effect)
    // Uses BlurDialog + Haze to implement frosted glass effect sampling host page content.
    // ─────────────────────────────────────────────────────────────────────────
    if (!showDeleteConfirm) {
        APlayerDialogTemplate(
            onDismissRequest = onDismissRequest,
            // Pass the host HazeState to ensure the first-level operations panel can sample current page background.
            hazeState = hazeState,
            // Pass user settings to BlurDialog; Material mode will skip internal Haze related textureBlur modifiers.
            glassEffectMode = glassEffectMode,
            // Action Dialog Adaptive Padding (Trim horizontal-mode padding slightly before widening the shell)
            // Landscape content gains room from the wider shell while the inner padding stays compact enough for two balanced columns.
            contentPadding = if (useLandscapeActionLayout) {
                PaddingValues(horizontal = 20.dp, vertical = 20.dp)
            } else {
                PaddingValues(horizontal = 24.dp, vertical = 24.dp)
            },
            // Action Dialog Width Policy (Use a wider shell only for non-portrait action menus)
            // Portrait keeps the prior 460.dp cap; landscape receives enough horizontal space for identity/status and destructive commands to sit side by side.
            dialogMaxWidth = if (useLandscapeActionLayout) {
                AudiobookActionLandscapeDialogMaxWidth
            } else {
                AudiobookActionPortraitDialogMaxWidth
            },
            // Blur radius, background color, and tint are adaptively configured inside BlurDialog, no longer passed from the call point.
            scrollable = true,
            headerAlignment = Alignment.CenterHorizontally,
            sectionSpacing = 0.dp,
            title = {
                // Portrait Header Slot (Keep the existing stacked dialog identity placement)
                // Landscape renders identity inside the left column, so the template title slot is populated only for the portrait flow to avoid duplicate book headers.
                if (!useLandscapeActionLayout) {
                    AudiobookActionIdentityHeader(
                        book = book,
                        coverRequestScene = coverRequestScene,
                        coverSize = AudiobookActionPortraitCoverSize
                    )
                }
            },
            body = {
                if (useLandscapeActionLayout) {
                    // Landscape Body Dispatch (Use the horizontal action-menu composition for non-portrait windows)
                    // The branch keeps the first-level menu short on landscape devices while leaving the nested delete confirmation dialog unchanged.
                    AudiobookActionLandscapeContent(
                        book = book,
                        coverRequestScene = coverRequestScene,
                        onEditBook = onEditBook,
                        onUpdateReadStatus = onUpdateReadStatus,
                        onForceRegenerate = onForceRegenerate,
                        onShowDeleteConfirm = { showDeleteConfirm = true },
                        onDismissRequest = onDismissRequest
                    )
                } else {

                Spacer(modifier = Modifier.height(16.dp))

                // Subtle divider line with 0.5f opacity to reduce visual weight
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(0.75f)
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
                        text = stringResource(R.string.home_action_mark_read_status),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Read Status Choice Group Semantics (Expose the three status chips as one exclusive group)
                            // Grouping lets assistive services understand Not Started, In Progress, and Finished as alternatives within the same read-status domain decision.
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Read Status Color Mapping (Bind each status to a restrained semantic accent)
                        // These softer tones keep green/blue/purple meaning while avoiding high-saturation button blocks inside the glass dialog.
                        val statusList = listOf(
                            ReadStatusChipSpec(
                                status = AudiobookSchema.ReadStatus.NOT_STARTED,
                                label = stringResource(R.string.filter_not_started),
                                accentColor = Color(0xFF72D38A)
                            ),
                            ReadStatusChipSpec(
                                status = AudiobookSchema.ReadStatus.IN_PROGRESS,
                                label = stringResource(R.string.filter_in_progress),
                                accentColor = Color(0xFF7DB7FF)
                            ),
                            ReadStatusChipSpec(
                                status = AudiobookSchema.ReadStatus.FINISHED,
                                label = stringResource(R.string.filter_finished),
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
                    color = MaterialTheme.colorScheme.onSurface.copy(0.75f)
                )

                // Edit Metadata Entry (Expose optional host-owned metadata editing from the shared action menu)
                // Keeping this as a nullable callback lets Home add the route while other hosts can reuse the dialog without gaining an edit dependency.
                if (onEditBook != null) {
                    Surface(
                        onClick = {
                            onEditBook(book.id)
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
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.edit_book_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Edit Metadata Supplemental Copy (Match the information density of other action rows)
                                // The edit entry now explains that it opens manual metadata and cover editing, so users can distinguish it from automatic regeneration at a glance.
                                Text(
                                    text = stringResource(R.string.home_action_edit_subtitle),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

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
                                text = stringResource(R.string.home_action_regenerate_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.home_action_regenerate_subtitle),
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
                                text = stringResource(R.string.home_action_remove_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.home_action_remove_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                }
            },
            actions = {
                // Cancel Action (Close the action menu without mutating the selected audiobook)
                // Keeps command placement inside the shared template action row while the actual dismissal remains a page-owned callback.
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.action_cancel))
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
            // Secondary confirmation panel reuses the host page shared backdrop, keeping background sampling source consistent with first-level panel.
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
                    text = stringResource(R.string.home_action_remove_confirm_title),
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
                    text = stringResource(R.string.home_action_remove_confirm_body, book.title),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(20.dp))
            },
            actions = {
                TextButton(
                    onClick = { showDeleteConfirm = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
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
                    Text(stringResource(R.string.home_action_remove_confirm_button))
                }
            }
        )
    }
}

/**
 * Audiobook Action Landscape Content (Arrange identity and commands in two columns)
 *
 * Keeps the selected audiobook identity and read-status controls on the left while placing management commands on the right, reducing vertical pressure in landscape windows without changing the host-owned callbacks.
 */
@Composable
private fun AudiobookActionLandscapeContent(
    book: AudiobookActionDialogBook,
    coverRequestScene: String,
    onEditBook: ((String) -> Unit)?,
    // Update Read Status Callback: Change readStatus parameter to ReadStatus enum.
    onUpdateReadStatus: (String, AudiobookSchema.ReadStatus) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onShowDeleteConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Landscape Column Height Coupling (Let the command column adapt to the identity column)
            // Intrinsic height gives both landscape columns a shared measured height, so the right-side commands can distribute their vertical gaps from real dialog content instead of a fixed spacing token.
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AudiobookActionLandscapeColumnSpacing)
    ) {
        // Landscape Identity Column (Group stable book context with the read-status domain decision)
        // Placing identity and status together keeps the right column focused on one-shot management commands and makes the two-column dialog scannable on short windows.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AudiobookActionIdentityHeader(
                book = book,
                coverRequestScene = coverRequestScene,
                coverSize = AudiobookActionLandscapeCoverSize,
                titleMaxLines = 2
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            AudiobookActionReadStatusSection(
                book = book,
                onUpdateReadStatus = onUpdateReadStatus,
                onDismissRequest = onDismissRequest
            )
        }

        // Landscape Command Column (Move destructive and maintenance actions beside the identity column)
        // The command list fills the shared landscape row height so Edit, Regenerate, and Remove can spread out naturally beside the denser identity/status column.
        Column(
            modifier = Modifier
                .weight(1f)
                // Landscape Command Height Fill (Use the row's intrinsic height as adaptive spacing budget)
                // Filling the matched row height lets Arrangement.SpaceBetween calculate gaps from available vertical room while keeping each command row's own tap target unchanged.
                .fillMaxHeight(),
            // Landscape Command Adaptive Gaps (Distribute the three command rows across the available height)
            // SpaceBetween replaces the old fixed 4.dp spacing so short landscape windows stay compact and taller landscape/tablet dialogs breathe without extra constants.
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            AudiobookActionCommandList(
                book = book,
                onEditBook = onEditBook,
                onForceRegenerate = onForceRegenerate,
                onShowDeleteConfirm = onShowDeleteConfirm,
                onDismissRequest = onDismissRequest
            )
        }
    }
}

/**
 * Audiobook Action Identity Header (Render selected book identity inside action menus)
 *
 * Shares the cover, title, and creator presentation between portrait and landscape variants so orientation changes do not create separate identity semantics or image-loading behavior.
 */
@Composable
private fun AudiobookActionIdentityHeader(
    book: AudiobookActionDialogBook,
    coverRequestScene: String,
    coverSize: Dp,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 1
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AudiobookActionCover(
            coverPath = CoverImageSourceSelector.small(
                thumbnailPath = book.thumbnailPath,
                coverPath = book.coverPath
            ),
            coverLastUpdated = book.lastScannedAt,
            requestScene = coverRequestScene,
            modifier = Modifier.size(coverSize)
        )

        Column(modifier = Modifier.weight(1f)) {
            // Action Dialog Title Clamp (Keep selected audiobook title within the active identity column)
            // Portrait keeps a single stable title line, while landscape may use two lines because the widened dialog isolates identity from the command list.
            Text(
                text = book.title,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )

            // Action Dialog Creator Subtitle (Show author and narrator only when metadata exists)
            // Empty creator fields produce no spacer or text, keeping anonymous/imported items visually compact in both orientations.
            if (book.author.isNotBlank() || book.narrator.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatPeopleSubtitle(book.author, book.narrator),
                    style = MaterialTheme.typography.bodyMedium,
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
}

/**
 * Audiobook Action Read Status Section (Render the exclusive read-status choices)
 *
 * Provides the same selectable group used by the portrait body so TalkBack and Switch Access keep one consistent read-status decision model across orientations.
 */
@Composable
private fun AudiobookActionReadStatusSection(
    book: AudiobookActionDialogBook,
    // Update Read Status Callback: Change readStatus parameter to ReadStatus enum.
    onUpdateReadStatus: (String, AudiobookSchema.ReadStatus) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.home_action_mark_read_status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Read Status Choice Group Semantics (Expose the three status chips as one exclusive group)
                // Grouping lets assistive services understand Not Started, In Progress, and Finished as alternatives within the same read-status domain decision.
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Read Status Color Mapping (Bind each status to a restrained semantic accent)
            // These softer tones keep green/blue/purple meaning while avoiding high-saturation button blocks inside the glass dialog.
            val statusList = listOf(
                ReadStatusChipSpec(
                    status = AudiobookSchema.ReadStatus.NOT_STARTED,
                    label = stringResource(R.string.filter_not_started),
                    accentColor = Color(0xFF72D38A)
                ),
                ReadStatusChipSpec(
                    status = AudiobookSchema.ReadStatus.IN_PROGRESS,
                    label = stringResource(R.string.filter_in_progress),
                    accentColor = Color(0xFF7DB7FF)
                ),
                ReadStatusChipSpec(
                    status = AudiobookSchema.ReadStatus.FINISHED,
                    label = stringResource(R.string.filter_finished),
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
                    // Read Status Equal Width (Divide the available row width evenly among all statuses)
                    // Keeping the weighted row mirrors portrait behavior and preserves predictable touch targets inside the landscape identity column.
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Audiobook Action Command List (Render one-shot management actions)
 *
 * Centralizes landscape command rows so optional edit support, forced regeneration, and deletion entry behavior remain controlled by the same host callbacks as the portrait dialog.
 */
@Composable
private fun AudiobookActionCommandList(
    book: AudiobookActionDialogBook,
    onEditBook: ((String) -> Unit)?,
    onForceRegenerate: (String) -> Unit,
    onShowDeleteConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    // Edit Metadata Command (Expose optional host-owned metadata editing from the shared action menu)
    // Keeping this nullable mirrors the portrait branch and prevents reusable hosts from acquiring an edit route dependency.
    if (onEditBook != null) {
        AudiobookActionCommandRow(
            icon = Icons.Rounded.Edit,
            title = stringResource(R.string.edit_book_title),
            subtitle = stringResource(R.string.home_action_edit_subtitle),
            tint = MaterialTheme.colorScheme.onSurface,
            titleColor = MaterialTheme.colorScheme.onSurface,
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = {
                onEditBook(book.id)
                onDismissRequest()
            }
        )
    }

    AudiobookActionCommandRow(
        icon = Icons.Rounded.Refresh,
        title = stringResource(R.string.home_action_regenerate_title),
        subtitle = stringResource(R.string.home_action_regenerate_subtitle),
        tint = MaterialTheme.colorScheme.onSurface,
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = {
            onForceRegenerate(book.id)
            onDismissRequest()
        }
    )

    AudiobookActionCommandRow(
        icon = Icons.Rounded.Delete,
        title = stringResource(R.string.home_action_remove_title),
        subtitle = stringResource(R.string.home_action_remove_subtitle),
        tint = MaterialTheme.colorScheme.error,
        titleColor = MaterialTheme.colorScheme.error,
        subtitleColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
        onClick = onShowDeleteConfirm
    )
}

/**
 * Audiobook Action Command Row (Reusable row for action menu commands)
 *
 * Keeps icon placement, text clamping, and row padding identical across landscape commands so the wider dialog reads as one coherent command group.
 */
@Composable
private fun AudiobookActionCommandRow(
    icon: ImageVector,
    title: String,
    tint: Color,
    titleColor: Color,
    subtitleColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// Audiobook Action Adaptive Dimension Tokens (Keep dialog sizing decisions beside the shared action menu)
// Portrait retains the prior Material-style dialog width, while landscape gets enough horizontal capacity for the new two-column menu without altering other dialogs.
private val AudiobookActionPortraitDialogMaxWidth = 460.dp
private val AudiobookActionLandscapeDialogMaxWidth = 640.dp
private val AudiobookActionPortraitCoverSize = 64.dp
private val AudiobookActionLandscapeCoverSize = 72.dp
private val AudiobookActionLandscapeColumnSpacing = 20.dp

// Read Status Chip Spec (Pairs each read-status command with its label and semantic accent)
// Keeps color mapping data outside the composable rendering branch so chip drawing remains small and predictable.
// Read Status Chip Spec: Use type-safe ReadStatus enum for status field.
private data class ReadStatusChipSpec(
    val status: AudiobookSchema.ReadStatus,
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
    val choiceStateDescription = stringResource(
        if (selected) {
            R.string.accessibility_choice_selected
        } else {
            R.string.accessibility_choice_unselected
        }
    )
    // Read Status Press Feedback Source (Disable the default selectable ripple while keeping press state scoped)
    // The chip already exposes selection through its fill, border, dot, text weight, and semantics, so the extra background indication would make the compact visual area feel heavier.
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            // Read Status Accessibility Shell (Separate the touch target from the compact visual chip)
            // The outer node owns the 48.dp radio semantics while the inner Surface keeps the existing status style at a 32.dp Material-like height.
            .defaultMinSize(
                minWidth = ReadStatusChipMinimumTouchTarget,
                minHeight = ReadStatusChipMinimumTouchTarget
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics {
                stateDescription = choiceStateDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // Read Status Visual Height (Keep the rendered pill compact while preserving the outer 48.dp hit area)
                // This mirrors the shared choice-chip split without changing the dialog's existing equal-width row layout.
                .height(ReadStatusChipVisualHeight),
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
}

// Read Status Chip Dimension Tokens (Separate visual compactness from accessible interaction size)
// The dialog keeps its existing pill style while matching the shared choice control contract of 32.dp visuals inside a 48.dp selectable target.
private val ReadStatusChipVisualHeight = 32.dp
private val ReadStatusChipMinimumTouchTarget = 48.dp

@Composable
private fun AudiobookActionCover(
    coverPath: String?,
    coverLastUpdated: Long,
    requestScene: String,
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
                        scene = requestScene
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
