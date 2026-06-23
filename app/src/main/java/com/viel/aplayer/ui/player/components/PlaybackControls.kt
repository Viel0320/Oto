package com.viel.aplayer.ui.player.components

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.media.SeekStepPresentation
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.shared.settings.PlaybackSeekStepConfig
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import com.viel.aplayer.ui.player.PlaybackControlActions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Renders explicit playback transport controls.
 *
 * The outer buttons now move across chapter boundaries while speed and sleep
 * timer commands are owned by bottom navigation, keeping hidden long-press
 * settings out of the main transport row.
 */
@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    playbackSeekStepConfig: PlaybackSeekStepConfig,
    actions: PlaybackControlActions,
    modifier: Modifier = Modifier,
    buttonColor: Color = MaterialTheme.colorScheme.primaryContainer,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    hazeState: HazeState? = null
) {
    val previousChapterContentDescription = stringResource(R.string.playback_previous_chapter_content_description)
    val rewindContentDescription = stringResource(SeekStepPresentation.backwardLabel(playbackSeekStepConfig.backward))
    val playPauseContentDescription = stringResource(
        if (isPlaying) R.string.playback_pause_content_description else R.string.playback_play_content_description
    )
    val forwardContentDescription = stringResource(SeekStepPresentation.forwardLabel(playbackSeekStepConfig.forward))
    val nextChapterContentDescription = stringResource(R.string.playback_next_chapter_content_description)

    val contentColor = if (buttonColor.luminance() > 0.5f) Color.Black else Color.White

    /**
     * Full-player side of the play/pause shared element. Matches both mini-player glyphs (compact and
     * pill) via SharedElementKeys.mini2playerPlayPause() so the icon flies between the surfaces. Inert
     * when the mini-origin scope is absent (direct open, previews).
     */
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val mini2PlayerTargetScope = LocalMini2PlayerTargetScope.current
    val playPauseSharedModifier = if (sharedTransitionScope != null && mini2PlayerTargetScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = SharedElementKeys.mini2playerPlayPause()),
                animatedVisibilityScope = mini2PlayerTargetScope,
                boundsTransform = { _, _ -> tween(durationMillis = 300) }
            )
        }
    } else {
        Modifier
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = actions.onPreviousChapter, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = previousChapterContentDescription,
                modifier = Modifier.size(32.dp)
            )
        }

        IconButton(onClick = actions.onSkipBackward, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(SeekStepPresentation.backwardIcon(playbackSeekStepConfig.backward)),
                contentDescription = rewindContentDescription,
                modifier = Modifier.size(32.dp)
            )
        }

        val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null

        if (isBlur) {
            val playPauseShape = CircleShape
            val glassModifier = Modifier
                .size(80.dp)
                .clip(playPauseShape)
                .hazeEffect(
                    state = hazeState,
                    style = HazeMaterials.ultraThin()
                )
            Surface(
                onClick = actions.onPlayPauseClick,
                modifier = glassModifier,
                shape = playPauseShape,
                color = Color.Transparent,
                border = null,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PlayPauseMorphIcon(
                        isPlaying = isPlaying,
                        contentDescription = playPauseContentDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(40.dp)
                            .then(playPauseSharedModifier)
                    )
                }
            }
        } else {
            FilledIconButton(
                onClick = actions.onPlayPauseClick,
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = buttonColor,
                    contentColor = contentColor
                )
            ) {
                PlayPauseMorphIcon(
                    isPlaying = isPlaying,
                    contentDescription = playPauseContentDescription,
                    tint = contentColor,
                    modifier = Modifier
                        .size(40.dp)
                        .then(playPauseSharedModifier)
                )
            }
        }

        IconButton(onClick = actions.onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(SeekStepPresentation.forwardIcon(playbackSeekStepConfig.forward)),
                contentDescription = forwardContentDescription,
                modifier = Modifier.size(32.dp)
            )
        }

        IconButton(onClick = actions.onNextChapter, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = nextChapterContentDescription,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * Renders the transport play/pause button as a morphing AnimatedVectorDrawable.
 *
 * The single `avd_play_pause` asset starts as the play triangle and morphs into
 * the pause bars, so [isPlaying] simply drives `atEnd`: false shows play, true
 * morphs to pause, and toggling reverses the same animation instead of swapping
 * two static icons. The asset is white fill+stroke, so the active [tint] is
 * applied at the call site to match each render branch (primary on glass,
 * computed contentColor on the filled button).
 */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun PlayPauseMorphIcon(
    isPlaying: Boolean,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val image = AnimatedImageVector.animatedVectorResource(R.drawable.avd_play_pause)
    val painter = rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = isPlaying)
    Image(
        painter = painter,
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier
    )
}

@Preview(apiLevel = 36)
@Composable
fun PlaybackControlsPreview() {
    APlayerTheme {
        Surface {
            PlaybackControls(
                isPlaying = false,
                playbackSeekStepConfig = PlaybackSeekStepConfig(),
                actions = PlaybackControlActions(),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
