package com.viel.aplayer.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Subtitles
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
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Decoupled player bottom icon navigation.
 *
 * Each tab keeps its localized title as the accessibility label while the
 * visible selection state is represented by switching between filled and
 * outlined variants. Every nav target uses the same 56.dp circular touch
 * target and 32.dp icon size as the transport controls.
 *
 * Speed and sleep-timer controls live at the outer edges of the same navigation
 * row so the playback transport can dedicate its outer buttons to chapter
 * movement while preserving the existing tap and long-press behavior.
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

    // Wrap the bottom navigation component with Column.
    //
    // Instead of applying navigationBarsPadding() directly here, we precisely control the height by sequentially placing a 16.dp anti-mistouch Spacer and the system navigationBarsPadding Spacer at the bottom.
    // This ensures the navigation click interaction area remains elevated while matching the transport button hit area.
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            val tabs = listOf(
                BottomNavTabItem(
                    title = stringResource(R.string.player_tab_bookmarks),
                    mode = PlayerScreenMode.BOOKMARKS,
                    activeIcon = Icons.Filled.Bookmark,
                    inactiveIcon = Icons.Outlined.Bookmark
                ),
                BottomNavTabItem(
                    title = stringResource(R.string.player_tab_subtitles),
                    mode = PlayerScreenMode.SUBTITLES,
                    activeIcon = Icons.Filled.Subtitles,
                    inactiveIcon = Icons.Outlined.Subtitles
                ),
                BottomNavTabItem(
                    title = stringResource(R.string.player_tab_related),
                    mode = PlayerScreenMode.RELATED,
                    activeIcon = Icons.AutoMirrored.Filled.LibraryBooks,
                    inactiveIcon = Icons.AutoMirrored.Outlined.LibraryBooks
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavActionButton(
                    modifier = Modifier.size(56.dp),
                    contentDescription = playbackSpeedContentDescription,
                    onClickLabel = playbackSpeedCycleActionLabel,
                    onClick = playbackActions.onCyclePlaybackSpeed,
                    onLongClickLabel = playbackSpeedResetActionLabel,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        playbackActions.onResetPlaybackSpeed()
                    }
                ) {
                    if (playbackSpeed == 1.0f && !isSpeedManualMode) {
                        Icon(
                            Icons.Rounded.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
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

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEach { tab ->
                        val tabInteractionSource = remember(tab.mode) { MutableInteractionSource() }
                        val isSelected = selectedTab == tab.mode
                        BottomNavTabButton(
                            modifier = Modifier.size(56.dp),
                            contentDescription = tab.title,
                            selected = isSelected,
                            onClick = { onTabSelected(tab.mode) },
                            interactionSource = tabInteractionSource
                        ) {
                            Icon(
                                imageVector = if (isSelected) tab.activeIcon else tab.inactiveIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 1f else 0.58f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                BottomNavActionButton(
                    modifier = Modifier.size(56.dp),
                    contentDescription = sleepTimerContentDescription,
                    onClickLabel = sleepTimerCycleActionLabel,
                    onClick = playbackActions.onCycleSleepTimer,
                    onLongClickLabel = sleepTimerCancelActionLabel,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        playbackActions.onCancelSleepTimer()
                    }
                ) {
                    if (selectedSleepTimer == 0) {
                        Icon(
                            Icons.Rounded.Snooze,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
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
        }
        
        // WindowClass integration.
        //
        // Use the unified WindowClass interface to obtain the current orientation, isolating LocalConfiguration and improving layout consistency.
        // Resolve Window Layout: Retrieve current viewport properties via standardized AppWindowSizeClass provider
        val windowClass = LocalAppWindowSizeClass.current
        val isLandscape = windowClass.isLandscape

        // Tab navigation spacing controls.
        //
        // In landscape, vertical space is highly precious and the gesture bar is typically on the side, so the spacer is reduced to 0.dp; in portrait, 16.dp is kept to protect gestures from accidental touches.
        val bottomSpacerHeight = if (isLandscape) 0.dp else 16.dp
        Spacer(modifier = Modifier.height(bottomSpacerHeight))
        
        // Use an independent Spacer to apply system bottom bar navigation insets, ensuring the interaction area is safely elevated without producing double padding.
        Spacer(modifier = Modifier.navigationBarsPadding())
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
 * Defines the icon and accessibility title for each player bottom tab.
 *
 * Keeping this as a narrow UI model avoids leaking icon choices into player
 * state while giving the navigation renderer a single source for tab visuals.
 */
private data class BottomNavTabItem(
    val title: String,
    val mode: PlayerScreenMode,
    val activeIcon: ImageVector,
    val inactiveIcon: ImageVector
)

/**
 * Renders a non-tab action inside the bottom navigation row.
 *
 * Speed and sleep are command buttons rather than mutually exclusive tabs, so
 * this helper keeps their click semantics out of the tab selectable group while
 * matching the same fixed touch target as the transport controls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomNavActionButton(
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
 */
@Composable
private fun BottomNavTabButton(
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
