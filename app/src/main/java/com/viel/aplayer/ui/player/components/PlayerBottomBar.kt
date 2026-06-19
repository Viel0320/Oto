package com.viel.aplayer.ui.player.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.ui.common.layout.AppWindowSizeClass
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.player.PlaybackControlActions
import com.viel.aplayer.ui.player.PlayerScreenMode
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Slot-based bottom bar scaffold for the player screen.
 *
 * This is a layout-only container with no knowledge of speed, sleep, or tab
 * concerns. It fixes the shared geometry — 24.dp horizontal padding, a 48.dp
 * tall row split as start action / centered tab group / end action — and owns
 * the orientation-aware bottom inset so callers never duplicate that policy.
 *
 * The [tabs] slot runs inside a [selectableGroup] so TalkBack treats its
 * children as a single mutually-exclusive tab set; [start] and [end] sit
 * outside that group as standalone command buttons.
 */
@Composable
fun PlayerBottomBar(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    tabs: @Composable RowScope.() -> Unit
) {
    // Wrap the bottom bar with Column.
    //
    // Instead of applying navigationBarsPadding() directly here, we precisely control the height by sequentially placing a 16.dp anti-mistouch Spacer and the system navigationBarsPadding Spacer at the bottom.
    // This ensures the navigation click interaction area remains elevated while matching the transport button hit area.
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                start()

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    content = tabs
                )

                end()
            }
        }

    }
}

/**
 * Decoupled player bottom icon navigation.
 *
 * Pre-assembled over [PlayerBottomBar]: speed sits in the start slot, the
 * bookmark/subtitle/related tabs fill the centered tab group, and the
 * sleep-timer sits in the end slot. Each tab keeps its localized title as the
 * accessibility label while the visible selection state switches between filled
 * and outlined icon variants. Every nav target uses the same 48.dp circular
 * touch target and 32.dp icon size as the transport controls.
 *
 * Speed and sleep-timer debounced toast feedback stays in this layer so it
 * follows the controls that change those values, leaving [PlayerBottomBar]
 * purely about layout.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomNavTabs(
    selectedTab: PlayerScreenMode,
    playbackSpeed: Float,
    selectedSleepTimer: Int,
    isSpeedManualMode: Boolean,
    playbackActions: PlaybackControlActions,
    onTabSelected: (PlayerScreenMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val sleepTimerContentDescription = stringResource(R.string.settings_sleep_timer_title)
    val playbackSpeedContentDescription = stringResource(R.string.playback_speed_content_description)
    val playbackSpeedText = stringResource(R.string.playback_speed_value, playbackSpeed.toString())
    val playbackSpeedCycleActionLabel = stringResource(R.string.playback_speed_cycle_action)
    val playbackSpeedResetActionLabel = stringResource(R.string.playback_speed_reset_action)
    val sleepTimerCycleActionLabel = stringResource(R.string.sleep_timer_cycle_action)
    val sleepTimerCancelActionLabel = stringResource(R.string.sleep_timer_cancel_action)

    // Speed feedback remains tied to the control that changes speed, even after the control moves from the transport row into bottom navigation.
    var lastSpeed by remember { mutableFloatStateOf(playbackSpeed) }
    LaunchedEffect(playbackSpeed) {
        if (playbackSpeed != lastSpeed) {
            delay(1500.milliseconds)
            val message = if (playbackSpeed == 1.0f) {
                FeedbackMessages.playbackSpeedReset()
            } else {
                FeedbackMessages.playbackSpeedChanged(formatPlaybackSpeedForFeedback(playbackSpeed))
            }
            playbackActions.onShowToast(message)
            lastSpeed = playbackSpeed
        }
    }

    // Sleep feedback follows the relocated sleep action so timer changes still produce one debounced localized toast.
    var lastTimer by remember { mutableIntStateOf(selectedSleepTimer) }
    LaunchedEffect(selectedSleepTimer) {
        if (selectedSleepTimer != lastTimer) {
            delay(1500.milliseconds)
            val message = when (selectedSleepTimer) {
                0 -> FeedbackMessages.sleepTimerOff()
                -1 -> FeedbackMessages.sleepTimerFiveSeconds()
                -2 -> FeedbackMessages.sleepTimerEndOfChapter()
                else -> FeedbackMessages.sleepTimerMinutes(selectedSleepTimer)
            }
            playbackActions.onShowToast(message)
            lastTimer = selectedSleepTimer
        }
    }

    PlayerBottomBar(
        modifier = modifier,
        start = {
            PlayerSpeedBottomButton(
                playbackSpeed = playbackSpeed,
                playbackSpeedText = playbackSpeedText,
                isSpeedManualMode = isSpeedManualMode,
                contentDescription = playbackSpeedContentDescription,
                onClickLabel = playbackSpeedCycleActionLabel,
                onClick = playbackActions.onCyclePlaybackSpeed,
                onLongClickLabel = playbackSpeedResetActionLabel,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    playbackActions.onResetPlaybackSpeed()
                }
            )
        },
        end = {
            PlayerSleepTimerBottomButton(
                selectedSleepTimer = selectedSleepTimer,
                contentDescription = sleepTimerContentDescription,
                onClickLabel = sleepTimerCycleActionLabel,
                onClick = playbackActions.onCycleSleepTimer,
                onLongClickLabel = sleepTimerCancelActionLabel,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    playbackActions.onCancelSleepTimer()
                }
            )
        }
    ) {
        PlayerBookmarkBottomTabButton(
            selected = selectedTab == PlayerScreenMode.BOOKMARKS,
            onClick = { onTabSelected(PlayerScreenMode.BOOKMARKS) }
        )
        PlayerSubtitlesBottomTabButton(
            selected = selectedTab == PlayerScreenMode.SUBTITLES,
            onClick = { onTabSelected(PlayerScreenMode.SUBTITLES) }
        )
        PlayerRelatedBottomTabButton(
            selected = selectedTab == PlayerScreenMode.RELATED,
            onClick = { onTabSelected(PlayerScreenMode.RELATED) }
        )
    }
}

// ==========================================
// Jetpack Compose @Preview Code Area
// ==========================================

// 1. Preview of active Tab.
//
// Simulates selecting "SUBTITLES" by default, supporting dynamic click switching via Live Edit in Android Studio preview panel.
@Preview(name = "BottomNavTabs - Active Tab", showBackground = true)
@Composable
fun BottomNavTabsPreview_Active() {
    APlayerTheme {
        // Preview environment setup.
        //
        // Explicitly inject portrait configuration presets for bottom bar preview.
        CompositionLocalProvider(
            // Provide AppWindowSizeClass: Inject portrait configuration preset for preview
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                var selectedTab by remember { mutableStateOf(PlayerScreenMode.SUBTITLES) }
                BottomNavTabs(
                    selectedTab = selectedTab,
                    playbackSpeed = 1.0f,
                    selectedSleepTimer = 0,
                    isSpeedManualMode = false,
                    playbackActions = PlaybackControlActions(),
                    onTabSelected = { selectedTab = it }
                )
            }
        }
    }
}

// 2. Preview of inactive Tabs.
//
// Simulates returning to the main player (PlayerScreenMode.PLAYER) where all tabs use outlined icons and inactive color.
@Preview(name = "BottomNavTabs - All Inactive", showBackground = true)
@Composable
fun BottomNavTabsPreview_Inactive() {
    APlayerTheme {
        // Preview environment setup.
        //
        // Explicitly inject portrait configuration presets for the bottom bar inactive indicator state preview.
        CompositionLocalProvider(
            // Provide AppWindowSizeClass: Inject portrait configuration preset for preview
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                var selectedTab by remember { mutableStateOf(PlayerScreenMode.PLAYER) }
                BottomNavTabs(
                    selectedTab = selectedTab,
                    playbackSpeed = 1.25f,
                    selectedSleepTimer = -2,
                    isSpeedManualMode = true,
                    playbackActions = PlaybackControlActions(),
                    onTabSelected = { selectedTab = it }
                )
            }
        }
    }
}

/**
 * Renders the playback speed command in the bottom bar start slot.
 *
 * Keeping this as a button-level composable prevents speed rendering rules from
 * being hidden inside the tab group, while preserving the long-press reset
 * contract owned by playback actions.
 */
@Composable
private fun PlayerSpeedBottomButton(
    playbackSpeed: Float,
    playbackSpeedText: String,
    isSpeedManualMode: Boolean,
    contentDescription: String,
    onClickLabel: String,
    onClick: () -> Unit,
    onLongClickLabel: String,
    onLongClick: () -> Unit
) {
    BottomNavActionButton(
        modifier = Modifier.size(48.dp),
        contentDescription = contentDescription,
        onClickLabel = onClickLabel,
        onClick = onClick,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick
    ) {
        if (playbackSpeed == 1.0f && !isSpeedManualMode) {
            Icon(
                Icons.Rounded.Speed,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
            )
        } else {
            Text(
                text = playbackSpeedText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Renders the sleep timer command in the bottom bar end slot.
 *
 * The timer keeps its own compact label mapping here so the slot caller only
 * wires commands and does not need to know how timer sentinel values are shown.
 */
@Composable
private fun PlayerSleepTimerBottomButton(
    selectedSleepTimer: Int,
    contentDescription: String,
    onClickLabel: String,
    onClick: () -> Unit,
    onLongClickLabel: String,
    onLongClick: () -> Unit
) {
    BottomNavActionButton(
        modifier = Modifier.size(48.dp),
        contentDescription = contentDescription,
        onClickLabel = onClickLabel,
        onClick = onClick,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick
    ) {
        if (selectedSleepTimer == 0) {
            Icon(
                Icons.Rounded.Snooze,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
            )
        } else {
            val displayText = when (selectedSleepTimer) {
                -1 -> stringResource(R.string.playback_sleep_timer_seconds_short, 5)
                -2 -> stringResource(R.string.playback_sleep_timer_chapter_short)
                else -> stringResource(R.string.playback_sleep_timer_minutes_short, selectedSleepTimer)
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Renders the bookmarks tab as a first-class slot button.
 *
 * This keeps the tab order visible at the call site and avoids rebuilding the
 * bottom bar from an anonymous list of tab metadata.
 */
@Composable
private fun PlayerBookmarkBottomTabButton(
    selected: Boolean,
    onClick: () -> Unit
) {
    PlayerModeBottomTabButton(
        selected = selected,
        title = stringResource(R.string.player_tab_bookmarks),
        animatedIcon = R.drawable.avd_tab_bookmark,
        onClick = onClick
    )
}

/**
 * Renders the subtitles tab as a first-class slot button.
 *
 * Keeping subtitles separate from the other buttons makes its player-mode
 * switch explicit without moving mode-selection logic into the layout scaffold.
 */
@Composable
private fun PlayerSubtitlesBottomTabButton(
    selected: Boolean,
    onClick: () -> Unit
) {
    PlayerModeBottomTabButton(
        selected = selected,
        title = stringResource(R.string.player_tab_subtitles),
        animatedIcon = R.drawable.avd_tab_subtitles,
        onClick = onClick
    )
}

/**
 * Renders the related-books tab as a first-class slot button.
 *
 * The auto-mirrored AVD stays isolated here because only this tab depends on
 * layout direction for its visual language.
 */
@Composable
private fun PlayerRelatedBottomTabButton(
    selected: Boolean,
    onClick: () -> Unit
) {
    PlayerModeBottomTabButton(
        selected = selected,
        title = stringResource(R.string.player_tab_related),
        animatedIcon = R.drawable.avd_tab_related,
        onClick = onClick
    )
}

/**
 * Renders the shared selectable shape for player mode tabs.
 *
 * The specific tab buttons own titles and AVD resources, while this helper owns
 * the common semantics, sizing, interaction source, and selected tint behavior.
 * Selection drives an AnimatedVectorDrawable morph (outline to filled) via
 * [rememberAnimatedVectorPainter]; the painter is tinted with onSurface so the
 * single white-filled asset adapts to light, dark, and dynamic color schemes,
 * with the inactive variant dimmed through the tint alpha.
 */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun PlayerModeBottomTabButton(
    selected: Boolean,
    title: String,
    @DrawableRes animatedIcon: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember(title) { MutableInteractionSource() }
    val image = AnimatedImageVector.animatedVectorResource(animatedIcon)
    val painter = rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = selected)
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.58f)
    BottomNavTabButton(
        modifier = Modifier.size(48.dp),
        contentDescription = title,
        selected = selected,
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Renders a non-tab action inside the bottom navigation row.
 *
 * Speed and sleep are command buttons rather than mutually exclusive tabs, so
 * this helper keeps their click semantics out of the tab selectable group while
 * matching the same fixed touch target as the transport controls. Exposed for
 * reuse by callers that fill [PlayerBottomBar]'s start/end slots.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomNavActionButton(
    modifier: Modifier = Modifier,
    contentDescription: String,
    onClickLabel: String,
    onClick: () -> Unit,
    onLongClickLabel: String,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit
 ) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .semantics {
                this.contentDescription = contentDescription
            }
            .combinedClickable(
                onClickLabel = onClickLabel,
                onClick = onClick,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                role = Role.Button
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * Renders a selectable navigation target inside the bottom navigation row.
 *
 * The selectable state stays on the button itself so TalkBack can announce the
 * active tab while the icon variant switches between filled and outlined.
 * Exposed for reuse by callers that fill [PlayerBottomBar]'s tabs slot.
 */
@Composable
fun BottomNavTabButton(
    modifier: Modifier = Modifier,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .semantics {
                this.contentDescription = contentDescription
            }
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private fun formatPlaybackSpeedForFeedback(speed: Float): String {
    // Playback Speed Formatting (Keep feedback arguments copy-neutral)
    // Trims the trailing decimal from whole-number speeds while preserving fractional values such as 0.75 or 1.25.
    val text = speed.toString()
    return text.trimEnd('0').trimEnd('.')
}
