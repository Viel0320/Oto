package com.viel.oto.ui.common

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
import com.viel.oto.R
import com.viel.oto.application.library.LibraryReadStatus
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import dev.chrisbanes.haze.HazeState

/**
 * Reusable payload for audiobook action menus.
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
    val readStatus: LibraryReadStatus?
)

/**
 * Reusable audiobook long-press menu.
 *
 * Derives the audiobook action menu and its delete confirmation dialog from the shared dialog template.
 * The host owns page dialog state and business callbacks, while this component owns only rendering and the nested confirmation step inside the active audiobook flow.
 */
@Composable
fun AudiobookActionDialog(
    book: AudiobookActionDialogBook?,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    coverRequestScene: String = "audiobook-action-dialog-cover",
    onDismissRequest: () -> Unit,
    onEditBook: ((String) -> Unit)? = null,
    onUpdateReadStatus: (String, LibraryReadStatus) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit
) {
    if (book == null) return

    var showDeleteConfirm by remember { mutableStateOf(false) }
    val useLandscapeActionLayout = LocalAppWindowSizeClass.current.isLandscape

    if (!showDeleteConfirm) {
        OtoDialogTemplate(
            onDismissRequest = onDismissRequest,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            contentPadding = if (useLandscapeActionLayout) {
                PaddingValues(horizontal = 20.dp, vertical = 20.dp)
            } else {
                PaddingValues(horizontal = 24.dp, vertical = 24.dp)
            },
            dialogMaxWidth = if (useLandscapeActionLayout) {
                AudiobookActionLandscapeDialogMaxWidth
            } else {
                AudiobookActionPortraitDialogMaxWidth
            },
            scrollable = true,
            headerAlignment = Alignment.CenterHorizontally,
            sectionSpacing = 0.dp,
            title = {
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

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(0.75f)
                )

                Spacer(modifier = Modifier.height(16.dp))

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
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val statusList = listOf(
                            ReadStatusChipSpec(
                                status = LibraryReadStatus.NOT_STARTED,
                                label = stringResource(R.string.filter_not_started),
                                accentColor = Color(0xFF72D38A)
                            ),
                            ReadStatusChipSpec(
                                status = LibraryReadStatus.IN_PROGRESS,
                                label = stringResource(R.string.filter_in_progress),
                                accentColor = Color(0xFF7DB7FF)
                            ),
                            ReadStatusChipSpec(
                                status = LibraryReadStatus.FINISHED,
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
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(0.75f)
                )

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
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteConfirm) {
        OtoDialogTemplate(
            onDismissRequest = { showDeleteConfirm = false },
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = false,
            headerAlignment = Alignment.CenterHorizontally,
            sectionSpacing = 0.dp,
            icon = {
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
 * Arrange identity and commands in two columns.
 *
 * Keeps the selected audiobook identity and read-status controls on the left while placing management commands on the right, reducing vertical pressure in landscape windows without changing the host-owned callbacks.
 */
@Composable
private fun AudiobookActionLandscapeContent(
    book: AudiobookActionDialogBook,
    coverRequestScene: String,
    onEditBook: ((String) -> Unit)?,
    onUpdateReadStatus: (String, LibraryReadStatus) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onShowDeleteConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AudiobookActionLandscapeColumnSpacing)
    ) {
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

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
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
 * Render selected book identity inside action menus.
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
            Text(
                text = book.title,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )

            if (book.author.isNotBlank() || book.narrator.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatPeopleSubtitle(book.author, book.narrator),
                    style = MaterialTheme.typography.bodyMedium,
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
 * Render the exclusive read-status choices.
 *
 * Provides the same selectable group used by the portrait body so TalkBack and Switch Access keep one consistent read-status decision model across orientations.
 */
@Composable
private fun AudiobookActionReadStatusSection(
    book: AudiobookActionDialogBook,
    onUpdateReadStatus: (String, LibraryReadStatus) -> Unit,
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
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val statusList = listOf(
                ReadStatusChipSpec(
                    status = LibraryReadStatus.NOT_STARTED,
                    label = stringResource(R.string.filter_not_started),
                    accentColor = Color(0xFF72D38A)
                ),
                ReadStatusChipSpec(
                    status = LibraryReadStatus.IN_PROGRESS,
                    label = stringResource(R.string.filter_in_progress),
                    accentColor = Color(0xFF7DB7FF)
                ),
                ReadStatusChipSpec(
                    status = LibraryReadStatus.FINISHED,
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
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Render one-shot management actions.
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
 * Reusable row for action menu commands.
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

private val AudiobookActionPortraitDialogMaxWidth = 460.dp
private val AudiobookActionLandscapeDialogMaxWidth = 640.dp
private val AudiobookActionPortraitCoverSize = 64.dp
private val AudiobookActionLandscapeCoverSize = 72.dp
private val AudiobookActionLandscapeColumnSpacing = 20.dp

private data class ReadStatusChipSpec(
    val status: LibraryReadStatus,
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
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
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
                .height(ReadStatusChipVisualHeight),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            border = BorderStroke(0.8.dp, borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
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
