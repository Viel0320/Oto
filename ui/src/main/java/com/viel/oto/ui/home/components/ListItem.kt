package com.viel.oto.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.shared.formatCompactDuration
import com.viel.oto.ui.common.CoverImageVariant
import com.viel.oto.ui.common.LazyCoverImage
import com.viel.oto.ui.common.formatPeopleSubtitle
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.motion.LocalSharedTransitionScope
import com.viel.oto.ui.motion.SharedElementKeys


private val HomeListRowMinHeight = 72.dp
private val HomeListRowVerticalPadding = 8.dp
private val HomeListCoverSize = 56.dp
private val HomeListMetadataGap = 16.dp
private val HomeListTrailingTouchTargetSize = 48.dp
private val HomeListTrailingVisualOffset = 8.dp

/**
 * Renders the dense Home list row with explicit foundation layout instead of Material3 ListItem.
 *
 * The public API and visual slots stay stable for callers, while the implementation avoids the
 * Material ListItem slot wrapper, text-style providers, and container color locals that were being
 * created for every row in the lazy grid.
 * Leading row inset follows AppWindowSizeClass so Home, Search, Related, and Recovery lists share one responsive gutter.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    bookId: String = "",
    title: String,
    author: String,
    narrator: String,
    duration: Long,
    onClick: () -> Unit,
    coverPath: String? = null,
    coverLastUpdated: Long = 0L,
    progressPercent: Int? = null,
    isDetailTargetActive: Boolean = false,
    sharedElementKey: String? = null,
    onLongClick: () -> Unit = {},
    onPlayClick: () -> Unit = {},
    openActionLabel: String? = null,
    playActionLabel: String? = null,
    moreActionsLabel: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues? = null
) {
    val newBadgeText = stringResource(R.string.common_new_badge)
    val unknownText = stringResource(R.string.common_unknown)
    val metadataSeparator = stringResource(R.string.common_metadata_separator)
    val playContentDescription = stringResource(R.string.playback_play_content_description)
    val resolvedOpenActionLabel = openActionLabel ?: stringResource(R.string.book_open_action)
    val resolvedPlayActionLabel = playActionLabel ?: stringResource(R.string.book_play_action)
    val resolvedMoreActionsLabel = moreActionsLabel ?: stringResource(R.string.book_more_actions_action)
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val titleStyle = typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val subtitleStyle = typography.bodyMedium
    val metadataStyle = typography.labelSmall
    val primaryTextColor = colorScheme.onSurface
    val secondaryTextColor = colorScheme.onSurfaceVariant
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HomeListRowMinHeight)
            .combinedClickable(
                onClickLabel = resolvedOpenActionLabel,
                onClick = onClick,
                onLongClickLabel = resolvedMoreActionsLabel,
                onLongClick = onLongClick
            )
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(resolvedOpenActionLabel) {
                        onClick()
                        true
                    },
                    CustomAccessibilityAction(resolvedPlayActionLabel) {
                        onPlayClick()
                        true
                    },
                    CustomAccessibilityAction(resolvedMoreActionsLabel) {
                        onLongClick()
                        true
                    }
                )
            }
            .padding(
                contentPadding ?: PaddingValues(
                    start = rowHorizontalPadding,
                    end = rowHorizontalPadding - HomeListTrailingVisualOffset,
                    top = HomeListRowVerticalPadding,
                    bottom = HomeListRowVerticalPadding
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListCoverSharedSource(
            bookId = bookId,
            coverPath = coverPath,
            coverLastUpdated = coverLastUpdated,
            isDetailTargetActive = isDetailTargetActive,
            sharedElementKey = sharedElementKey,
            placeholderColor = colorScheme.surfaceVariant,
            fallbackIconTint = secondaryTextColor,
            modifier = Modifier.size(HomeListCoverSize)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = HomeListMetadataGap),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = titleStyle,
                color = primaryTextColor
            )
            Text(
                formatPeopleSubtitle(author, narrator, fallback = unknownText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = subtitleStyle,
                color = secondaryTextColor
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (progressPercent != null && progressPercent > 0) {
                    Text(
                        text = "$progressPercent%",
                        style = metadataStyle,
                        color = secondaryTextColor
                    )
                    Text(text = metadataSeparator, style = metadataStyle, color = secondaryTextColor)
                } else {
                    Text(
                        text = newBadgeText,
                        style = metadataStyle,
                        color = secondaryTextColor
                    )
                    Text(text = metadataSeparator, style = metadataStyle, color = secondaryTextColor)
                }

                Text(
                    text = formatCompactDuration(duration),
                    style = metadataStyle,
                    color = secondaryTextColor
                )
            }
        }

        Box(
            modifier = Modifier
                .widthIn(min = HomeListTrailingTouchTargetSize),
            contentAlignment = Alignment.Center
        ) {
            if (trailingContent != null) {
                trailingContent()
            } else {
                DefaultListTrailingAction(
                    onClick = onPlayClick,
                    contentDescription = playContentDescription,
                    tint = secondaryTextColor
                )
            }
        }
    }
}

/**
 * Provides the default play affordance without Material IconButton's extra provider stack.
 *
 * The 48dp target preserves touch and accessibility expectations while the icon color is passed
 * explicitly, keeping the row free of inherited content-color lookups.
 */
@Composable
private fun DefaultListTrailingAction(
    onClick: () -> Unit,
    contentDescription: String,
    tint: Color
) {
    Box(
        modifier = Modifier
            .size(HomeListTrailingTouchTargetSize)
            .clip(CircleShape)
            .clickable(
                onClickLabel = contentDescription,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun ListCoverSharedSource(
    bookId: String,
    coverPath: String?,
    coverLastUpdated: Long,
    isDetailTargetActive: Boolean,
    sharedElementKey: String?,
    placeholderColor: Color,
    fallbackIconTint: Color,
    modifier: Modifier = Modifier
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val resolvedSharedElementKey = sharedElementKey
        ?: bookId.takeIf { it.isNotBlank() }?.let { SharedElementKeys.homeList2DetailCover(it) }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = !isDetailTargetActive,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            val list2DetailSourceScope = this
            val animatedCoverCornerRadius by list2DetailSourceScope.transition.animateDp(
                label = "home_list_cover_corner_radius",
                transitionSpec = { tween(300) }
            ) { enterExitState ->
                if (enterExitState == EnterExitState.Visible) 8.dp else 24.dp
            }
            val animatedCoverShape = RoundedCornerShape(animatedCoverCornerRadius)
            val isKeyConsistent = resolvedSharedElementKey != null &&
                bookId.isNotBlank() &&
                resolvedSharedElementKey.contains(bookId)

            val coverSharedElementModifier = if (
                !LocalInspectionMode.current &&
                isKeyConsistent &&
                sharedTransitionScope != null
            ) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        rememberSharedContentState(key = resolvedSharedElementKey),
                        animatedVisibilityScope = list2DetailSourceScope,
                        clipInOverlayDuringTransition = OverlayClip(animatedCoverShape)
                    )
                }
            } else {
                Modifier
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(coverSharedElementModifier)
                    .clip(animatedCoverShape)
                    .background(placeholderColor)
            ) {
                LazyCoverImage(
                    sourcePath = coverPath,
                    lastUpdated = coverLastUpdated,
                    variant = CoverImageVariant.ThumbnailSmall,
                    scene = "home-list-cover",
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = fallbackIconTint
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "New Book", apiLevel = 36)
@Composable
fun ListItemNewPreview() {
    OtoTheme(dynamicColor = false) {
        Surface {
            ListItem(
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                duration = 3600000L,
                progressPercent = 0,
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "In Progress", apiLevel = 36)
@Composable
fun ListItemProgressPreview() {
    OtoTheme(dynamicColor = false) {
        Surface {
            ListItem(
                title = "Mystery in the Woods",
                author = "Arthur Conan Doyle",
                narrator = "Stephen Fry",
                duration = 7200000L,
                progressPercent = 45,
                onClick = {}
            )
        }
    }
}
