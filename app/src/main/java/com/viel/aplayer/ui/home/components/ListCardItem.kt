package com.viel.aplayer.ui.home.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
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
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.LazyCoverImage
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.motion.LocalHomeRecent2DetailSourceScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.animation.AnimatedVisibility as SharedSourceVisibility

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
    coverLastUpdated: Long = 0L, // Used to pass cover file self-healing milliseconds timestamp to trigger responsive cache breaking
    /*
     * Detail Target Activity Flag (Selected source visibility control)
     *
     * Hides only the cover of the recent card that is currently opening in Detail, allowing
     * SharedTransitionLayout to observe a source exit paired with the Detail target entry.
     */
    isDetailTargetActive: Boolean = false,
    // New long-press callback to support the long-press shortcut menu in the recently added/played section
    onLongClick: () -> Unit = {},
    // Cardgroup Glass Mode Parameter (Allow the card renderer to follow global frosted glass mode)
    // Defaults to Material so previews and callers outside Home can render without needing settings state.
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    /*
     * Shared Element Key (Home recent cover transition identity)
     *
     * Supplies a route-level shared-element key so the recent-card artwork can animate into
     * the matching detail-page artwork when the selected book opens.
     */
    sharedElementKey: String? = null,
    // Cardgroup Width Mode (Allow specialized callers to override the standard horizontal-row width)
    // Home Cardgroup rows keep the fixed 160.dp width so every section remains a single horizontal carousel.
    fillAvailableWidth: Boolean = false,
    preferredWidth: Dp = 160.dp,
    // Card Assistive Action Labels (Keep card commands discoverable across list surfaces)
    // Callers can override these labels when a card is reused for a scene-specific command set.
    openActionLabel: String? = null,
    moreActionsLabel: String? = null
) {
    // Localized Card Metadata Fallback (Keep blank author/narrator placeholders translatable)
    // The card receives book data from the catalog, but empty metadata fallback text is owned by the app UI.
    val unknownText = stringResource(R.string.common_unknown)
    val resolvedOpenActionLabel = openActionLabel ?: stringResource(R.string.book_open_action)
    val resolvedMoreActionsLabel = moreActionsLabel ?: stringResource(R.string.book_more_actions_action)

    Column(
        modifier = modifier
            .then(if (fillAvailableWidth) Modifier.fillMaxWidth() else Modifier.width(preferredWidth))
            .clip(RoundedCornerShape(16.dp))
            // Use combinedClickable gesture listener instead to handle click and long-press events
            .combinedClickable(
                onClickLabel = resolvedOpenActionLabel,
                onClick = onClick,
                onLongClickLabel = resolvedMoreActionsLabel,
                onLongClick = onLongClick
            )
            // Merge the card into a single semantics node: the cover, progress badge, title, and
            // author lines collapse into one accessibility item, and the merged tree the a11y
            // geometry sort traverses each frame gets one node per card instead of three-plus.
            .semantics(mergeDescendants = true) {
                // Card Custom Actions (Expose open and action-menu shortcuts on the card node)
                // Switch Access and TalkBack users can discover the same menu previously hidden behind long press.
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
            glassEffectMode = glassEffectMode,
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
    glassEffectMode: GlassEffectMode,
    isDetailTargetActive: Boolean,
    sharedElementKey: String?,
    modifier: Modifier = Modifier
) {
    // Haze State Initialization (Haze State Allocation) Declare a local HazeState to coordinate background and badge blur.
    val itemHazeState = remember { HazeState() }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    /*
     * Recent Cover Key Resolution (Fallback motion identity)
     *
     * Prefers an explicit shared-element key from the parent section, then falls back to the
     * local book ID so previews and future callers can still opt into Home -> Detail motion.
     */
    val resolvedSharedElementKey = sharedElementKey
        ?: bookId.takeIf { it.isNotBlank() }?.let { SharedElementKeys.home2DetailCover(it) }

    Box(modifier = modifier) {
        SharedSourceVisibility(
            visible = !isDetailTargetActive,
            /*
             * Source Visibility Fade Policy (Restore standalone source opacity easing)
             *
             * Keeps the recent-card cover fading in and out around the shared-element handoff,
             * while the navigation gate prevents rapid Detail re-entry from retargeting this chain.
             */
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            /*
             * Recent Cover Source Scope (Item-level shared-element source boundary)
             *
             * Provides the source scope from the selected card's own visibility transition so
             * Home->Detail behaves like the working mini2player handoff: source exits, target enters.
             */
            val home2DetailSourceScope = this@SharedSourceVisibility
            /*
             * Recent Cover Corner Radius Transition (Source shape interpolation)
             *
             * Animates the recent-card artwork radius from the card cover shape to the larger
             * detail cover shape while this source cover exits, keeping the overlay clip aligned
             * with the shared-element bounds morph instead of snapping between fixed radii.
             */
            val animatedCoverCornerRadius by home2DetailSourceScope.transition.animateDp(
                label = "recent_cover_corner_radius",
                transitionSpec = { tween(300) }
            ) { enterExitState ->
                if (enterExitState == EnterExitState.Visible) 16.dp else 24.dp
            }
            val animatedCoverShape = RoundedCornerShape(animatedCoverCornerRadius)
            /*
             * Recent Cover Shared Element Binding (Source cover motion endpoint)
             *
             * Applies the shared-element modifier only when the app shared scope, item-level source
             * scope, and route key are available. This keeps Home->Detail independent from player scopes.
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
                        animatedVisibilityScope = home2DetailSourceScope,
                        clipInOverlayDuringTransition = OverlayClip(animatedCoverShape)
                    )
                }
            } else {
                Modifier
            }

            CompositionLocalProvider(
                /*
                 * Home To Detail Source Scope Provider (Recent-card source isolation)
                 *
                 * Publishes only this cover's item-level visibility scope so sibling covers and
                 * unrelated motion channels cannot reuse the selected source transition state.
                 */
                LocalHomeRecent2DetailSourceScope provides home2DetailSourceScope
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(coverSharedElementModifier)
                        .clip(animatedCoverShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    // Restore Haze to Cover (Restore haze source modifier to background cover)
                    // Mount haze modifier directly on the inner cover box instead of the outer parent box.
                    // This matches Chris Banes' Haze layout requirement: haze source must be sibling to hazeChild to capture pixel frames properly.
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
                        /*
                         * Lazy Card Cover Loading (Viewport-gated medium thumbnail request)
                         *
                         * Horizontal card rows can precompose nearby cards; delaying the medium
                         * thumbnail request until placement keeps decoding focused on visible cards.
                         * Crossfade is disabled here because section rows can reveal many cached
                         * covers during a fast swipe, and each fade would add avoidable frame work.
                         */
                        LazyCoverImage(
                            sourcePath = coverPath,
                            lastUpdated = coverLastUpdated,
                            variant = CoverImageVariant.ThumbnailMedium,
                            scene = "recently-cover",
                            modifier = Modifier.fillMaxSize(),
                            allowHardware = true,
                            bitmapConfig = null,
                            crossfade = false
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    RecentCoverProgressBadge(
                        progressText = progressText,
                        isBlur = isBlur,
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
    isBlur: Boolean,
    itemHazeState: HazeState,
    modifier: Modifier = Modifier
) {

    Surface(
        modifier = modifier.then(
            if (isBlur) {
                Modifier
                    // Clip corner radius at the very front of Modifier chain to prevent frosted glass overflow glitches
                    .clip(RoundedCornerShape(12.dp))
                    // Badge Glassmorphism (Apply the direct Haze material after clipping the badge bounds)
                    .hazeEffect(
                        state = itemHazeState,
                        style = HazeMaterials.ultraThin()
                    )
            } else {
                Modifier
            }
        ),
        color = if (isBlur) {
            // Opacity Setup (Prevent Double Alpha Stacking)
            // Under blur mode, since hazeChild has blended translucent background masks,
            // Surface background should be completely transparent to avoid opacity stacking.
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        },
        // Remove badge borders to establish a minimalist edge-free light-transmitting visual; no border configuration is passed here
        border = null,
        shape = if (isBlur) {
            RoundedCornerShape(12.dp)
        } else {
            RoundedCornerShape(8.dp)
        }
    ) {
        Text(
            text = progressText,
            // Widen horizontal padding from 6.dp to 10.dp to make the badge visual extension fuller and more spacious
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            textAlign = TextAlign.Center,
            // Use .copy(fontWeight = FontWeight.ExtraBold) explicitly to force ExtraBold weight, ensuring edge sharpness and outline clarity in small font sizes on frosted glass background.
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Preview(showBackground = true, name = "Recently Item NEW")
@Composable
fun CardgroupNewPreview() {
    APlayerTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            Cardgroup(
                bookId = "preview",
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                // Preview Badge Copy (Use the localized new-item marker so preview surfaces exercise the same text path as runtime cards)
                // The surrounding preview book metadata remains mock data, but the badge is app-authored UI copy.
                progressText = stringResource(R.string.common_new_badge),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Recently Item Progress")
@Composable
fun CardgroupProgressPreview() {
    APlayerTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            Cardgroup(
                bookId = "preview",
                title = "In the Megachurch",
                author = "Ryo Asai",
                narrator = "Unknown",
                progressText = "45%",
                onClick = {},
                // Preview Glass Mode Config (Configure Preview Glass Effect) Explicitly enable Haze frosted glass in preview.
                glassEffectMode = GlassEffectMode.Haze
            )
        }
    }
}
