package com.viel.aplayer.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import com.viel.aplayer.ui.player.PlayerScreenMode

// Decoupled player bottom Tab navigation component.
//
// The width is adaptively determined based on the actual measured width of the Tab text.
// It provides perfect center alignment and interpolated scaling animations when sliding between tabs.
@Composable
fun BottomNavTabs(
    selectedTab: PlayerScreenMode,
    onTabSelected: (PlayerScreenMode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Wrap the bottom navigation component with Column.
    //
    // Instead of applying navigationBarsPadding() directly here, we precisely control the height by sequentially placing a 16.dp anti-mistouch Spacer and the system navigationBarsPadding Spacer at the bottom.
    // This ensures the Tab click interaction area (height of 48.dp) is physically raised by 16.dp, completely avoiding accidental triggers from virtual navigation keys/gestures.
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            val tabs = listOf(
                // Player Tab Labels (Resolve player content tabs through resources)
                // These tab labels are visible navigation text and must track the app language instead of staying hard-coded English.
                stringResource(R.string.player_tab_bookmarks) to PlayerScreenMode.BOOKMARKS,
                stringResource(R.string.player_tab_subtitles) to PlayerScreenMode.SUBTITLES,
                stringResource(R.string.player_tab_related) to PlayerScreenMode.RELATED
            )

            val density = LocalDensity.current

            // Declare 3 independent mutableStateOf variables.
            //
            // This provides isolated records for the actual text width of each Tab.
            // Elegant default widths of (80, 70, 60.dp) are used for the first frame to ensure that the indicator width is never abruptly 0 before measurement is complete.
            var bookmarkTextWidth by remember { mutableStateOf(80.dp) }
            var subtitlesTextWidth by remember { mutableStateOf(70.dp) }
            var relatedTextWidth by remember { mutableStateOf(60.dp) }

            var lastActiveTab by remember { mutableStateOf(PlayerScreenMode.SUBTITLES) }
            LaunchedEffect(selectedTab) {
                if (selectedTab != PlayerScreenMode.PLAYER) {
                    lastActiveTab = selectedTab
                }
            }

            val isMainPlayer = selectedTab == PlayerScreenMode.PLAYER
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (isMainPlayer) 0f else 1f,
                animationSpec = tween(300),
                label = "indicator_alpha"
            )

            val indicatorOffset by animateFloatAsState(
                targetValue = lastActiveTab.index.toFloat(),
                animationSpec = if (indicatorAlpha == 0f) snap() else tween(300),
                label = "tab_indicator_offset"
            )

            // Dynamically read the corresponding independently measured text width after physical isolation based on the current active Tab index.
            val activeTabWidth = remember(lastActiveTab, bookmarkTextWidth, subtitlesTextWidth, relatedTextWidth) {
                when (lastActiveTab) {
                    PlayerScreenMode.BOOKMARKS -> bookmarkTextWidth
                    PlayerScreenMode.SUBTITLES -> subtitlesTextWidth
                    PlayerScreenMode.RELATED -> relatedTextWidth
                    else -> 70.dp
                }
            }

            val currentIndicatorWidth by animateDpAsState(
                targetValue = activeTabWidth,
                animationSpec = if (indicatorAlpha == 0f) snap() else tween(300),
                label = "tab_indicator_width"
            )

            val activeColor = MaterialTheme.colorScheme.onSurface

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                val width = size.width
                val tabWidth = width / 3
                val indWidthPx = currentIndicatorWidth.toPx()

                // Calculate physical center coordinates.
                //
                // 1. Bookmark is aligned left; its center is at half its width.
                // 2. Subtitles is aligned center; its center is always at the geometric midpoint of the middle 1/3 section.
                // 3. Related is aligned right; its right edge sits tight against the right of the canvas, so its center is at the total width minus half its own width.
                val centerX0 = bookmarkTextWidth.toPx() / 2f
                val centerX1 = tabWidth * 1.5f
                val centerX2 = width - relatedTextWidth.toPx() / 2f

                // Linear interpolation logic.
                //
                // Linearly interpolate between the three physical text centers based on the sliding percentage indicatorOffset.
                // This ensures the indicator is always 100% aligned with the actual center of the text during sliding transitions.
                val indicatorCenterX = if (indicatorOffset <= 1f) {
                    val t = indicatorOffset
                    centerX0 + (centerX1 - centerX0) * t
                } else {
                    val t = indicatorOffset - 1f
                    centerX1 + (centerX2 - centerX1) * t
                }

                // Position the left starting point fluidXPos of the indicator based on the animated width indWidthPx and the calculated center point.
                val fluidXPos = indicatorCenterX - indWidthPx / 2f

                // Adjust Y-axis height.
                //
                // Raise the starting y-coordinate of the indicator from size.height - 4.dp to size.height - 10.dp.
                // This reduces the physical distance between the indicator and the vertically centered Tab text (bottom is around 32.dp) from 12.dp to exactly 6.dp, halving the space.
                drawRoundRect(
                    color = activeColor.copy(alpha = indicatorAlpha),
                    topLeft = androidx.compose.ui.geometry.Offset(fluidXPos, size.height - 10.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(indWidthPx, 3.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }

            // M-18 Fix — Add selectableGroup to allow accessibility services (TalkBack/Switch Access) to recognize this as a mutually exclusive tab container group.
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, (title, mode) ->
                    // M-18 Fix — Use independent MutableInteractionSource for each Tab to avoid pressing/hover state crosstalk from sharing a single interactionSource.
                    val tabInteractionSource = remember(mode) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            // M-18 Fix — Change .clickable to .selectable, declaring selected state and Role.Tab to allow TalkBack to speak "Selected/Not selected".
                            .selectable(
                                selected = (selectedTab == mode),
                                onClick = { onTabSelected(mode) },
                                role = Role.Tab,
                                interactionSource = tabInteractionSource,
                                indication = null
                            ),
                        contentAlignment = when (index) {
                            0 -> Alignment.CenterStart
                            1 -> Alignment.Center
                            2 -> Alignment.CenterEnd
                            else -> Alignment.Center
                        }
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (selectedTab == mode) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 16.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selectedTab == mode) 1f else 0.6f),
                            modifier = Modifier.onSizeChanged { size ->
                                val textWidthDp = with(density) { size.width.toDp() }
                                when (index) {
                                    0 -> {
                                        if (bookmarkTextWidth != textWidthDp) bookmarkTextWidth = textWidthDp
                                    }
                                    1 -> {
                                        if (subtitlesTextWidth != textWidthDp) subtitlesTextWidth = textWidthDp
                                    }
                                    2 -> {
                                        if (relatedTextWidth != textWidthDp) relatedTextWidth = textWidthDp
                                    }
                                }
                            }
                        )
                    }
                }
            }
            
        }
        
        // WindowClass integration.
        //
        // Use the unified WindowClass interface to obtain the current orientation, isolating LocalConfiguration and improving layout consistency.
        val windowClass = LocalWindowClass.current
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
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                var selectedTab by remember { mutableStateOf(PlayerScreenMode.SUBTITLES) }
                BottomNavTabs(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }
        }
    }
}

// 2. Preview of inactive Tabs.
//
// Simulates returning to the main player (PlayerScreenMode.PLAYER) where the indicator collapses and all Tab texts display uniformly at a low 0.6f brightness.
@Preview(name = "BottomNavTabs - All Inactive", showBackground = true)
@Composable
fun BottomNavTabsPreview_Inactive() {
    APlayerTheme {
        // Preview environment setup.
        //
        // Explicitly inject portrait configuration presets for the bottom bar inactive indicator state preview.
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                var selectedTab by remember { mutableStateOf(PlayerScreenMode.PLAYER) }
                BottomNavTabs(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }
        }
    }
}
