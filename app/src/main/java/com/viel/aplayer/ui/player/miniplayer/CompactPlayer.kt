package com.viel.aplayer.ui.player.miniplayer

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState and modifiers.
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.common.AudioProgressBar
import com.viel.aplayer.ui.common.CoverImageRequestFactory
import com.viel.aplayer.ui.common.CoverImageVariant
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.LiquidGlassBorderMode
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import com.viel.aplayer.ui.motion.LocalMini2PlayerSourceScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import com.viel.aplayer.ui.player.MiniPlayerActions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

@OptIn(ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CompactMediaPlayer(
    bookId: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Audiobook Title",
    author: String = "Unknown",
    narrator: String = "",
    coverPath: String? = null,
    coverLastUpdated: Long = 0L,
    progress: () -> Float = { 0f },
    showProgressBar: Boolean = true,
    actions: MiniPlayerActions = MiniPlayerActions(),
    // Setup HazeState Parameter (Map backdrop parameter to HazeState) Changed LayerBackdrop to HazeState.
    hazeState: HazeState? = null,
    onClick: () -> Unit = {},
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Color Extracted Callback (Pass color callback to upstream MiniPlayerOverlay)
    // Invoked when Coil successfully decodes the Bitmap cover and retrieves its dominant color.
    onColorExtracted: ((Color) -> Unit)? = null,
) {

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerSourceScope = LocalMini2PlayerSourceScope.current

    /*
     * Outer Card Corner Radius Transition (Dynamic bounds shape interpolation)
     * Transition the outer card corner radius from the compact bar's 0.dp to the full screen's 28.dp.
     * Use target state checking to apply rounded corners when fully morphed, preventing straight corner overflow.
     */
    // Align transition durations: Set compact outer bounds corner radius transition to 300ms.
    val animatedCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "compact_bounds_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 0.dp else 28.dp
    }
        ?: remember { mutableStateOf(0.dp) }

    /*
     * Thumbnail Cover Corner Radius Transition (Smooth inner artwork morphing)
     * Interpolate the artwork cover corner radius between the compact card's 8.dp and
     * the full screen player's 24.dp, ensuring no visual pixel steps occur.
     */
    // Align transition durations: Set compact artwork cover corner radius transition to 300ms.
    val animatedCoverCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "compact_cover_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 8.dp else 24.dp
    }
        ?: remember { mutableStateOf(8.dp) }

    // Disable Bounds Transition: Bypass shared bounds morphing for CompactPlayer to let the player slide up/down instead of morphing, while preserving cover shared elements.
    val boundsModifier = Modifier

    // Setup Haze Mode Switch (Check if Haze mode is configured) Aligned to renamed Haze option.
    val isBlurMode = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    // Localized Player Copy (Resolve labels beside the composable that renders them)
    // These values feed visible fallback metadata and accessibility text, so they follow the active app locale instead of hard-coded English.
    val unknownText = stringResource(R.string.common_unknown)
    val unknownTitle = stringResource(R.string.common_unknown_title)
    val coverContentDescription = stringResource(R.string.media_cover_content_description)
    val playPauseContentDescription = stringResource(
        if (isPlaying) R.string.playback_pause_content_description else R.string.playback_play_content_description
    )
    // Mini Player Cover Assistive Actions (Expose cover gestures as named actions)
    // The artwork owns the hide shortcut, so labeling both click and long-click semantics makes the cover usable beyond touch-only gestures.
    val openMiniPlayerActionLabel = stringResource(R.string.mini_player_open_action)
    val hideMiniPlayerActionLabel = stringResource(R.string.mini_player_hide_action)

    Surface(
        onClick = onClick,
        modifier = modifier
            .then(boundsModifier)
            .fillMaxWidth()
            .let {
                if (isBlurMode) {
                    val compactShape = RoundedCornerShape(animatedCornerRadius)
                    it
                        .clip(compactShape)
                        .liquidGlassCompatEffect(
                            state = hazeState,
                            style = LiquidGlassStyle(
                                // Adaptive Glass Tint: Fallback to theme-based 12% tint (White in Dark, Black in Light) by leaving it Unspecified.
                                specularIntensity = 0.4f,
                                ambientResponse = 0.5f,
                                shape = compactShape,
                                // Compact Player Borderless Haze (Remove only the full-width compact player's glass outline)
                                // The pill mini-player keeps its circular cover border and glass outline, while this bottom compact bar uses blur without an edge stroke.
                                borderMode = LiquidGlassBorderMode.None
                            )
                        )
                } else {
                    it
                }
            },
        color = if (isBlurMode) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(animatedCornerRadius)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (showProgressBar) {
                // Apply Glass Mode (Propagate glassEffectMode to AudioProgressBar to render crystal glow progress on compact player)
                AudioProgressBar(
                    progress = progress,
                    onProgressChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    showKnob = false,
                    glassEffectMode = glassEffectMode
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Transition Key Consistency Validation (Prevent invalid or empty shared transition keys)
                // Ensures that the bookId is non-blank before attempting to apply the shared element transition.
                // If the bookId is empty, falls back to a normal transition.
                val isKeyConsistent = bookId.isNotBlank()

                val coverModifier = if (isKeyConsistent && sharedTransitionScope != null && mini2PlayerSourceScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            /*
                             * Compact Player Cover Key (Centralized artwork identity)
                             *
                             * Resolves the mini-player artwork key through SharedElementKeys so it
                             * stays aligned with the full-player MainCoverView key generation.
                            */
                            rememberSharedContentState(key = SharedElementKeys.mini2playerCover(bookId)),
                            animatedVisibilityScope = mini2PlayerSourceScope
                        )
                    }
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(coverModifier)
                        .clip(RoundedCornerShape(animatedCoverCornerRadius))
                        .combinedClickable(
                            onClickLabel = openMiniPlayerActionLabel,
                            onClick = onClick,
                            onLongClickLabel = hideMiniPlayerActionLabel,
                            onLongClick = actions.onHide
                        )
                ) {
                    if (coverPath != null) {
                        val context = LocalContext.current
                        val request = remember(coverPath, coverLastUpdated) {
                            CoverImageRequestFactory.build(
                                context = context,
                                sourcePath = coverPath,
                                lastUpdated = coverLastUpdated,
                                variant = CoverImageVariant.ThumbnailSmall,
                                scene = "compact-player-cover"
                            )
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = coverContentDescription,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            contentScale = ContentScale.Crop,
                            onSuccess = { successResult ->
                                val colorInt = ImageProcessor.getDominantColorFromDrawable(successResult.result.drawable)
                                // Cache Calculated Color: Write the extracted dominant color into the main process LruCache to speed up future renders.
                                ImageProcessor.putColorToCache(coverPath, colorInt)
                                onColorExtracted?.invoke(Color(colorInt))
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.takeIf { it.isNotBlank() } ?: unknownTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = formatPeopleSubtitle(
                            author.takeIf { it.isNotBlank() } ?: unknownText,
                            narrator.takeIf { it.isNotBlank() } ?: unknownText,
                            fallback = unknownText
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = actions.onPlayPauseClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = playPauseContentDescription,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
