package com.viel.aplayer.ui.home.components

// Setup ListCardItem Imports (Coil & Haze Integration)
// Added getValue and setValue import extensions to perfectly support Composable's 'by' property delegation logic.
// Introduce Haze related dependencies to draw high-performance frosted glass effects.
import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil.compose.AsyncImage
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import com.viel.aplayer.ui.motion.LocalHomeRecent2DetailSourceScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
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

    // Reset Color State on Cover Path Changes: Re-initialize coverColor state whenever the coverPath changes using remember(coverPath) and load the cached color synchronously if available.
    var coverColor by remember(coverPath) {
        mutableStateOf(com.viel.aplayer.media.parser.ImageProcessor.getCachedColor(coverPath)?.let { Color(it) })
    }

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
            .semantics {
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
            coverColor = coverColor,
            isDetailTargetActive = isDetailTargetActive,
            sharedElementKey = sharedElementKey,
            onColorExtracted = { coverColor = it },
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
    coverColor: Color?,
    isDetailTargetActive: Boolean,
    sharedElementKey: String?,
    onColorExtracted: (Color) -> Unit,
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
                        // Use Coil's onError callback to fully decouple the performance risk of calling File.exists() synchronously on the main thread during Composable recombination.
                        var isImageError by remember(coverPath) { mutableStateOf(false) }
                        if ((coverPath != null) && !isImageError) {
                            val context = LocalContext.current
                            // Cardgroup Thumbnail Strategy (Reuse the medium thumbnail size for cover-card layouts)
                            // Recently played cards and Home grid cards both match the 360px thumbnail output, keeping cache behavior shared across card surfaces.
                            // This ensures medium cards hit local thumbnails and the same Coil cache specifications, avoiding bringing large covers into the list.
                            val request = remember(coverPath, coverLastUpdated) {
                                CoverImageRequestFactory.build(
                                    context = context,
                                    sourcePath = coverPath,
                                    lastUpdated = coverLastUpdated,
                                    variant = CoverImageVariant.ThumbnailMedium,
                                    scene = "recently-cover",
                                    // Disable Hardware Cover (Pass allowHardware = false and Config.RGB_565 since this cover needs dominant color extraction)
                                    // Preempts Hardware Bitmaps from being loaded and bypasses the costly copy() call inside ImageProcessor.
                                    allowHardware = false,
                                    bitmapConfig = Bitmap.Config.RGB_565
                                )
                            }
                            AsyncImage(
                                model = request,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onSuccess = { successResult ->
                                    val colorInt = com.viel.aplayer.media.parser.ImageProcessor.getDominantColor(successResult.result.drawable)
                                    // Cache Calculated Color: Write the extracted dominant color into the main process LruCache to speed up future renders.
                                    com.viel.aplayer.media.parser.ImageProcessor.putColorToCache(coverPath, colorInt)
                                    onColorExtracted(Color(colorInt))
                                },
                                onError = {
                                    // Log Metric Handling (Decoupled Image Metrics Logging)
                                    // Card component only handles display degradation; success, failure, cancel, and hit rate logging are handled uniformly by image request listener, preventing duplicate logs.
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
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    RecentCoverProgressBadge(
                        progressText = progressText,
                        coverColor = coverColor,
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
    coverColor: Color?,
    isBlur: Boolean,
    itemHazeState: HazeState,
    modifier: Modifier = Modifier
) {
    // Progress Badge Styling (Frosted Glass/Material Adaptive)
    // Refactor the progress Badge container into an elegant frosted glass Surface supporting Haze.
    // When frosted glass is enabled, introduce custom adjustable opacity blur materials and translucent 0.5.dp border; fall back to native high-saturation Material container in traditional mode.
    // Theme Aware Progress Badge (Use LocalDarkTheme to resolve active theme state instead of system defaults) Read theme preference state for contrast calculation.
    val isDark = com.viel.aplayer.ui.common.theme.LocalDarkTheme.current

    // Contrast Stretching Algorithm (Luminance Contrast Tuning)
    // Upgrade to contrast-stretching algorithm based on luminance checking (RGB 65% physical channel color blending):
    // - Dark mode: If cover color extraction is dark (luminance < 0.5f), blend with pure white at 65% ratio (0.35f * rawColor + 0.65f), allowing text to shine warm-glow on dark frosted glass background.
    // - Light mode: If cover color extraction is light (luminance > 0.5f), blend with pure black at 65% ratio (0.35f * rawColor), suppressing brightness to prevent text from melting on milky semi-translucent glass.
    val resolvedColor = remember(coverColor, isDark, isBlur) {
        coverColor?.let { rawColor ->
            val lum = rawColor.luminance()
            // Haze Contrast Optimization (Force light tint processing in haze mode or dark theme to keep badge content clean and visible on frosted layouts) Perform white channel contrast stretching for low-luminance values.
            if (isDark || isBlur) {
                if (lum < 0.5f) {
                    // In dark mode or haze mode and cover color is dark, apply 65% white enhancement stretch to guarantee contrast (0.35 * rawColor + 0.65)
                    Color(
                        red = rawColor.red * 0.35f + 0.65f,
                        green = rawColor.green * 0.35f + 0.65f,
                        blue = rawColor.blue * 0.35f + 0.65f,
                        alpha = 1f
                    )
                } else {
                    rawColor
                }
            } else {
                if (lum > 0.5f) {
                    // In light mode and cover color is light, apply 65% black suppression stretch to guarantee recognition (0.35 * rawColor)
                    Color(
                        red = rawColor.red * 0.35f,
                        green = rawColor.green * 0.35f,
                        blue = rawColor.blue * 0.35f,
                        alpha = 1f
                    )
                } else {
                    rawColor
                }
            }
        }
    }

    Surface(
        modifier = modifier.then(
            if (isBlur) {
                Modifier
                    // Clip corner radius at the very front of Modifier chain to prevent frosted glass overflow glitches
                    .clip(RoundedCornerShape(12.dp))
                    // Badge Glassmorphism (Apply Haze Child Blur) Replace old blur with hazeChild regular style.
                    .liquidGlassCompatEffect(
                        state = itemHazeState,
                        style = LiquidGlassStyle(
                            // Badge Circular Shape (Use Compose shape instead of the liquid glass surface profile enum)
                            // LiquidGlassStyle.shape expects a Shape implementation, so CircleShape keeps the badge round while avoiding the unresolved Circle symbol.
                            shape = CircleShape
                        )
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
            color = if (isBlur) {
                // Smart Contrast Color Mapping (Dynamic Text Color)
                // Badge text uses cover color and smart contrast stretching:
                // - Light mode: use native primary theme color on fallback.
                // - Dark mode: use pure white (Color.White) to achieve 100% optimal contrast and premium frosted glass look.
                resolvedColor ?: if (isDark) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.primary
                }
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
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
