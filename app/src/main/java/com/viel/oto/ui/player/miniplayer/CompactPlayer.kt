package com.viel.oto.ui.player.miniplayer

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.oto.R
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.CoverImage
import com.viel.oto.ui.common.CoverImageVariant
import com.viel.oto.ui.common.formatPeopleSubtitle
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.motion.LocalMini2PlayerSourceScope
import com.viel.oto.ui.motion.LocalSharedTransitionScope
import com.viel.oto.ui.motion.SharedElementKeys
import com.viel.oto.ui.player.MiniPlayerActions
import com.viel.oto.ui.player.components.AudioProgressBar
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class, ExperimentalAnimationGraphicsApi::class)
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
    hazeState: HazeState? = null,
    onClick: () -> Unit = {},
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerSourceScope = LocalMini2PlayerSourceScope.current
    val screenHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding
    val animatedCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "compact_bounds_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 0.dp else 28.dp
    }
        ?: remember { mutableStateOf(0.dp) }

    val animatedCoverCornerRadius by mini2PlayerSourceScope?.transition?.animateDp(
        label = "compact_cover_corner_radius",
        transitionSpec = { tween(300) }
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 8.dp else 24.dp
    }
        ?: remember { mutableStateOf(8.dp) }

    val boundsModifier = if (sharedTransitionScope != null && mini2PlayerSourceScope != null) {
        with(sharedTransitionScope) {
            /**
             * Mirror the full player's bottom-anchored morph so collapsing (full -> mini) keeps the
             * same fixed bottom edge and matched tween(300) as expanding. RemeasureToBounds avoids
             * scaling the mini player's measured content while the shared bounds continue to carry the
             * bottom-flush source geometry. CompactPlayer is the phone variant only; the wide-screen pill
             * keeps the default sharedBounds behavior.
             */
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = SharedElementKeys.playerBounds()),
                animatedVisibilityScope = mini2PlayerSourceScope,
                boundsTransform = BoundsTransform { _, _ -> tween(durationMillis = 300) },
                resizeMode = RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(animatedCornerRadius))
            )
        }
    } else {
        Modifier
    }

    /**
     * Shares the play/pause glyph with the full player's transport button so the icon flies and
     * scales (32dp -> 40dp) between mini and full instead of cross-fading in place. Matched against
     * SharedElementKeys.mini2playerPlayPause() on the PlaybackControls side; tween(300) keeps it in
     * lockstep with the surface bounds morph.
     */
    val playPauseSharedModifier = if (sharedTransitionScope != null && mini2PlayerSourceScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = SharedElementKeys.mini2playerPlayPause()),
                animatedVisibilityScope = mini2PlayerSourceScope,
                boundsTransform = { _, _ -> tween(durationMillis = 300) }
            )
        }
    } else {
        Modifier
    }

    val isBlurMode = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    val unknownText = stringResource(R.string.common_unknown)
    val unknownTitle = stringResource(R.string.common_unknown_title)
    val coverContentDescription = stringResource(R.string.media_cover_content_description)
    val playPauseContentDescription = stringResource(
        if (isPlaying) R.string.playback_pause_content_description else R.string.playback_play_content_description
    )
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
                        .hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin()
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
                AudioProgressBar(
                    progress = progress,
                    onProgressChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    showKnob = false,
                    enableProgressSemantics = false,
                    glassEffectMode = glassEffectMode
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = screenHorizontalPadding, end = screenHorizontalPadding - 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isKeyConsistent = bookId.isNotBlank()

                val coverModifier = if (isKeyConsistent && sharedTransitionScope != null && mini2PlayerSourceScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = SharedElementKeys.mini2playerCover()),
                            animatedVisibilityScope = mini2PlayerSourceScope
                        )
                    }
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .then(coverModifier)
                        .clip(RoundedCornerShape(animatedCoverCornerRadius))
                        .combinedClickable(
                            onClickLabel = openMiniPlayerActionLabel,
                            onClick = onClick,
                            onLongClickLabel = hideMiniPlayerActionLabel,
                            onLongClick = actions.onHide
                        )
                ) {
                    CoverImage(
                        sourcePath = coverPath,
                        lastUpdated = coverLastUpdated,
                        variant = CoverImageVariant.ThumbnailSmall,
                        scene = "compact-player-cover",
                        contentDescription = coverContentDescription,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                        allowHardware = true,
                        bitmapConfig = null
                    ) {
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
                Box(
                    modifier = Modifier
                        .size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = actions.onPlayPauseClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        val playPauseImage = AnimatedImageVector.animatedVectorResource(R.drawable.avd_play_pause)
                        val playPausePainter = rememberAnimatedVectorPainter(
                            animatedImageVector = playPauseImage,
                            atEnd = isPlaying
                        )
                        Image(
                            painter = playPausePainter,
                            contentDescription = playPauseContentDescription,
                            modifier = Modifier
                                .size(32.dp)
                                .then(playPauseSharedModifier),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
            }
        }
    }
}
