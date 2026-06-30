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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.CoverImageVariant
import com.viel.oto.ui.common.LazyCoverImage
import com.viel.oto.ui.common.formatPeopleSubtitle
import com.viel.oto.ui.common.theme.LocalGlassEffectMode
import com.viel.oto.ui.common.theme.LocalIsBlur
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.motion.LocalHomeRecent2DetailSourceScope
import com.viel.oto.ui.motion.LocalSharedTransitionScope
import com.viel.oto.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.viel.oto.ui.common.icons.OtoIcons

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class,
    ExperimentalHazeMaterialsApi::class
)
@Composable
fun Cardgroup(
    bookId: String,
    title: String,
    author: String,
    narrator: String,
    progressText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    coverLastUpdated: Long = 0L,
    isDetailTargetActive: Boolean = false,
    onLongClick: () -> Unit = {},
    sharedElementKey: String? = null,
    fillAvailableWidth: Boolean = false,
    preferredWidth: Dp = 160.dp,
    openActionLabel: String? = null,
    moreActionsLabel: String? = null
) {
    val unknownText = stringResource(R.string.common_unknown)
    val resolvedOpenActionLabel = openActionLabel ?: stringResource(R.string.book_open_action)
    val resolvedMoreActionsLabel = moreActionsLabel ?: stringResource(R.string.book_more_actions_action)

    Column(
        modifier = modifier
            .then(if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier.width(preferredWidth))
            .clip(RoundedCornerShape(16.dp))
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
                    CustomAccessibilityAction(resolvedMoreActionsLabel) {
                        onLongClick()
                        true
                    }
                )
            }
            .padding(8.dp)
    ) {
        RecentCoverSharedSource(
            bookId = bookId,
            progressText = progressText,
            coverPath = coverPath,
            coverLastUpdated = coverLastUpdated,
            isDetailTargetActive = isDetailTargetActive,
            sharedElementKey = sharedElementKey,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatPeopleSubtitle(
                author.takeIf { it.isNotBlank() } ?: unknownText,
                narrator.takeIf { it.isNotBlank() } ?: unknownText,
                fallback = unknownText
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalHazeMaterialsApi::class)
private fun RecentCoverSharedSource(
    bookId: String,
    progressText: String,
    coverPath: String?,
    coverLastUpdated: Long,
    isDetailTargetActive: Boolean,
    sharedElementKey: String?,
    modifier: Modifier = Modifier
) {
    val itemHazeState = remember { HazeState() }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val isBlur = LocalIsBlur.current
    val resolvedSharedElementKey = sharedElementKey
        ?: bookId.takeIf { it.isNotBlank() }?.let { SharedElementKeys.home2DetailCover(it) }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = !isDetailTargetActive,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            val home2DetailSourceScope = this@AnimatedVisibility
            val animatedCoverCornerRadius by home2DetailSourceScope.transition.animateDp(
                label = "recent_cover_corner_radius",
                transitionSpec = { tween(300) }
            ) { enterExitState ->
                if (enterExitState == EnterExitState.Visible) 16.dp else 24.dp
            }
            val animatedCoverShape = RoundedCornerShape(animatedCoverCornerRadius)
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
                        animatedVisibilityScope = home2DetailSourceScope,
                        clipInOverlayDuringTransition = OverlayClip(animatedCoverShape)
                    )
                }
            } else {
                Modifier
            }

            CompositionLocalProvider(
                LocalHomeRecent2DetailSourceScope provides home2DetailSourceScope
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(coverSharedElementModifier)
                        .clip(animatedCoverShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isBlur) {
                                    Modifier.hazeSource(itemHazeState)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        LazyCoverImage(
                            sourcePath = coverPath,
                            lastUpdated = coverLastUpdated,
                            variant = CoverImageVariant.ThumbnailMedium,
                            scene = "recently-cover",
                            modifier = Modifier.fillMaxSize(),
                            allowHardware = true,
                            bitmapConfig = null
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    OtoIcons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    RecentCoverProgressBadge(
                        progressText = progressText,
                        itemHazeState = itemHazeState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalHazeMaterialsApi::class)
private fun RecentCoverProgressBadge(
    progressText: String,
    itemHazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val isBlur = LocalIsBlur.current

    Surface(
        modifier = modifier.then(
            if (isBlur) {
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .hazeEffect(
                        state = itemHazeState,
                        style = HazeMaterials.ultraThin()
                    )
            } else {
                Modifier
            }
        ),
        color = if (isBlur) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        },
        border = null,
        shape = if (isBlur) {
            RoundedCornerShape(12.dp)
        } else {
            RoundedCornerShape(8.dp)
        }
    ) {
        Text(
            text = progressText,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Preview(showBackground = true, name = "Recently Item NEW")
@Composable
fun CardgroupNewPreview() {
    OtoTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            Cardgroup(
                bookId = "preview",
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                progressText = stringResource(R.string.common_new_badge),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Recently Item Progress")
@Composable
fun CardgroupProgressPreview() {
    OtoTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            CompositionLocalProvider(
                LocalIsBlur provides true,
                LocalGlassEffectMode provides GlassEffectMode.Haze
            ) {
                Cardgroup(
                    bookId = "preview",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Unknown",
                    progressText = "45%",
                    onClick = {}
                )
            }
        }
    }
}
