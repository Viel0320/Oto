package com.viel.aplayer.ui.home.components

// Setup ListItem Imports (Coil & click delegate)
// Added combinedClickable import to respond to list item long press.
// Added ExperimentalFoundationApi import to shield compilation defects of experimental APIs.
// Added getValue and setValue import extensions to support Composable property delegation.
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.R
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.formatCompactDuration
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import androidx.compose.animation.AnimatedVisibility as ListSourceVisibility

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
    coverLastUpdated: Long = 0L, // Used to pass cover file self-healing milliseconds timestamp to trigger responsive cache breaking
    progressPercent: Int? = null,
    /*
     * Detail Target Activity Flag (Home-list source visibility control)
     *
     * Hides only the list thumbnail that opened the current detail overlay, preventing the
     * Home recent card for the same book from joining this list-specific transition channel.
     */
    isDetailTargetActive: Boolean = false,
    /*
     * Shared Element Key (Home list cover transition identity)
     *
     * Supplies the list-specific shared-element key so main-list artwork can animate into
     * Detail without sharing the Recent section's Home-to-detail key.
     */
    sharedElementKey: String? = null,
    // New onLongClick parameter to receive long-press events callback
    onLongClick: () -> Unit = {},
    onPlayClick: () -> Unit = {},
    // Book Row Assistive Action Labels (Allow callers to specialize row command names)
    // Defaults keep existing call sites accessible while specialized scenes can override the action text if a command means something narrower.
    openActionLabel: String? = null,
    playActionLabel: String? = null,
    moreActionsLabel: String? = null,
    // Replaceable Trailing Action (Allows non-home scenes to reuse the book row body with their own command)
    // Home keeps the default play button, while recovery can render a restore button without duplicating cover and metadata layout.
    trailingContent: (@Composable () -> Unit)? = null
) {
    // Localized List Row Copy (Resolve badge, metadata fallback, metadata separator, and play-button accessibility text)
    // Book titles and people names are library data, while NEW, row separators, and Play are app-authored UI labels.
    val newBadgeText = stringResource(R.string.common_new_badge)
    val unknownText = stringResource(R.string.common_unknown)
    val metadataSeparator = stringResource(R.string.common_metadata_separator)
    val playContentDescription = stringResource(R.string.playback_play_content_description)
    val resolvedOpenActionLabel = openActionLabel ?: stringResource(R.string.book_open_action)
    val resolvedPlayActionLabel = playActionLabel ?: stringResource(R.string.book_play_action)
    val resolvedMoreActionsLabel = moreActionsLabel ?: stringResource(R.string.book_more_actions_action)

    ListItem(
        // Replace original clickable with combinedClickable to listen to onClick and onLongClick gestures
        modifier = modifier
            .combinedClickable(
                onClickLabel = resolvedOpenActionLabel,
                onClick = onClick,
                onLongClickLabel = resolvedMoreActionsLabel,
                onLongClick = onLongClick
            )
            .semantics {
                // Book Row Custom Actions (Aggregate hidden and nested commands on the row)
                // Assistive technologies can open, play, or reveal the book action menu from the row without hunting for a nested icon or hidden long-press gesture.
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
            },
        headlineContent = {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    formatPeopleSubtitle(author, narrator, fallback = unknownText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val separator = metadataSeparator
                    val textStyle = MaterialTheme.typography.labelSmall
                    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

                    if (progressPercent != null && progressPercent > 0) {
                        Text(
                            text = "$progressPercent%",
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = separator, style = textStyle, color = textColor)
                    } else {
                        Text(
                            text = newBadgeText,
                            style = textStyle,
                            color = textColor
                        )
                        Text(text = separator, style = textStyle, color = textColor)
                    }

                    Text(
                        text = formatCompactDuration(duration),
                        style = textStyle,
                        color = textColor
                    )
                }
            }
        },
        leadingContent = {
            ListCoverSharedSource(
                bookId = bookId,
                coverPath = coverPath,
                coverLastUpdated = coverLastUpdated,
                isDetailTargetActive = isDetailTargetActive,
                sharedElementKey = sharedElementKey,
                modifier = Modifier.size(56.dp)
            )
        },
        trailingContent = {
            if (trailingContent != null) {
                trailingContent()
            } else {
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.offset(x = 8.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = playContentDescription)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun ListCoverSharedSource(
    bookId: String,
    coverPath: String?,
    coverLastUpdated: Long,
    isDetailTargetActive: Boolean,
    sharedElementKey: String?,
    modifier: Modifier = Modifier
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    /*
     * List Cover Key Resolution (List-only fallback motion identity)
     *
     * Keeps list thumbnails on the homeList2DetailCover channel, which is separate from the
     * Recent section's home2DetailCover channel even when both sections show the same book.
     */
    val resolvedSharedElementKey = sharedElementKey
        ?: bookId.takeIf { it.isNotBlank() }?.let { SharedElementKeys.homeList2DetailCover(it) }

    Box(modifier = modifier) {
        ListSourceVisibility(
            visible = !isDetailTargetActive,
            /*
             * List Source Visibility Fade Policy (Restore standalone source opacity easing)
             *
             * Keeps Home-list and Search-result thumbnails fading in and out around the shared
             * motion; rapid retarget protection now belongs to the app-level Detail gate.
             */
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            val list2DetailSourceScope = this@ListSourceVisibility
            /*
             * List Cover Corner Radius Transition (Source shape interpolation)
             *
             * Animates the compact list thumbnail from its 8.dp source shape to Detail's 24.dp
             * cover shape, without reusing the Recent section's 16.dp card radius.
             */
            val animatedCoverCornerRadius by list2DetailSourceScope.transition.animateDp(
                label = "home_list_cover_corner_radius",
                transitionSpec = { tween(300) }
            ) { enterExitState ->
                if (enterExitState == EnterExitState.Visible) 8.dp else 24.dp
            }
            val animatedCoverShape = RoundedCornerShape(animatedCoverCornerRadius)
            /*
             * List Cover Shared Element Binding (Source cover motion endpoint)
             *
             * Applies the shared-element modifier only to the selected list thumbnail channel,
             * leaving Recent cards and player artwork on their own independent keys.
             */
            // Transition Key Consistency Validation (Prevent mismatching shared transition keys)
            // Validates that the resolved transition key is non-null, the bookId is valid, and the key
            // is indeed associated with this bookId. If validation fails, falls back to a normal transition.
            val isKeyConsistent = resolvedSharedElementKey != null &&
                bookId.isNotBlank() &&
                resolvedSharedElementKey.contains(bookId)

            val coverSharedElementModifier = if (
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

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .then(coverSharedElementModifier),
                shape = animatedCoverShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                val isPreview = LocalInspectionMode.current
                // Define local image load error state, using Coil's async loading and onError callback to achieve zero main-thread sync disk I/O probing.
                var isImageError by remember(coverPath) { mutableStateOf(false) }
                if (!isPreview && (coverPath != null) && !isImageError) {
                    val context = LocalContext.current
                    // Thumbnail Small Caching (Optimize Memory and Cache Reuse)
                    // List item strictly uses ThumbnailSmall specification, allowing list, search thumbnail, and miniplayer to share 180px cache.
                    // Skips synchronous File.exists() calls, letting Coil handle file missing asynchronously and log results.
                    val request = remember(coverPath, coverLastUpdated) {
                        CoverImageRequestFactory.build(
                            context = context,
                            sourcePath = coverPath,
                            lastUpdated = coverLastUpdated,
                            variant = CoverImageVariant.ThumbnailSmall,
                            scene = "home-list-cover"
                        )
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = {
                            // Log Metric Handling (Decoupled Image Metrics Logging)
                            // UI layer only handles displaying placeholder when image fails; logging metrics are handled by request listener inside CoverImageRequestFactory.
                            isImageError = true
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
    APlayerTheme(dynamicColor = false) {
        Surface {
            ListItem(
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                duration = 3600000L,
                progressPercent = 0,
//                addedAt = System.currentTimeMillis(),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "In Progress", apiLevel = 36)
@Composable
fun ListItemProgressPreview() {
    APlayerTheme(dynamicColor = false) {
        Surface {
            ListItem(
                title = "Mystery in the Woods",
                author = "Arthur Conan Doyle",
                narrator = "Stephen Fry",
                duration = 7200000L,
                progressPercent = 45,
//                addedAt = System.currentTimeMillis() - 86400000,
                onClick = {}
            )
        }
    }
}
