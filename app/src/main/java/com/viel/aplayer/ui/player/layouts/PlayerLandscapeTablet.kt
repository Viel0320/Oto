package com.viel.aplayer.ui.player.layouts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BottomNavTabs
// Resolve window class dependencies (To replace redundant LocalWindowClass imports with the unified theme package structure)
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreenMode
import com.viel.aplayer.ui.player.PlayerUiState
import com.viel.aplayer.ui.player.components.PlayerControlPanel
import com.viel.aplayer.ui.player.components.PlayerLandscapeHeader
import com.viel.aplayer.ui.player.components.RelatedBooksView
import com.viel.aplayer.ui.player.components.SubtitlesView
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkListView
import dev.chrisbanes.haze.HazeState

/**
 * Tablet landscape player layout (Component rendering double-column player panels in wide screens)
 * Implements clean layouts for tablet screens and folding displays.
 * Applies symmetrical padding structures and spacing margins to provide professional visual hierarchies.
 */
@Composable
fun PlayerLandscapeTablet(
    currentPosition: Long,
    bufferedPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    currentChapter: PlayerChapterItem?,
    isPlaying: Boolean,
    playbackSpeed: Float,
    isSpeedManualMode: Boolean,
    bookmarkToDelete: PlayerBookmarkItem?,
    bookmarkToEdit: PlayerBookmarkItem?,
    bookmarkEditTitle: String,
    onRequestDeleteBookmark: (PlayerBookmarkItem) -> Unit,
    onRequestEditBookmark: (PlayerBookmarkItem) -> Unit,
    onBookmarkEditTitleChange: (String) -> Unit,
    onConfirmDeleteBookmark: () -> Unit,
    onConfirmUpdateBookmark: () -> Unit,
    onDismissBookmarkDialogs: () -> Unit,
    metadata: BookMetadataState,
    settings: com.viel.aplayer.ui.settings.PlayerSettingsState,
    actions: PlayerActions,
    fullUiState: PlayerUiState,
    currentMode: PlayerScreenMode,
    onModeChange: (PlayerScreenMode) -> Unit,
    animatedBgColor: Color,
    glassEffectMode: GlassEffectMode,
    // Player Floating Haze Source (Use the stable app-level sampler for player glass surfaces)
    // PlayerScreen passes the resolved app-level source so chapter sheets, bookmark dialogs, controls, and header menus stay on one HazeState.
    chapterSheetHazeState: HazeState?,
    // Color Extracted Callback (Pass color callback to downstream PlayerCover)
    // Invoked when Coil successfully decodes the Bitmap cover and retrieves its dominant color.
    onColorExtracted: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    // Resolve window dimensions (To query device width coordinates without reading LocalConfiguration parameters directly)
    val windowClass = LocalAppWindowSizeClass.current
    val density = LocalDensity.current
    
    // Sync Previous Mode: Tracks the previous playback tab mode to allow custom transition logic (e.g. crossfading to PLAYER mode).
    // Updates synchronously inside LaunchedEffect when currentMode changes.
    var prevMode by remember { mutableStateOf(currentMode) }
    LaunchedEffect(currentMode) {
        prevMode = currentMode
    }

    // Title: Standardize landscape tablet dimensions (Replace dynamic screen width calculations with screenHorizontalPadding and fixed spacing)
    // Refactors landscape layout paddings to avoid screen density or dimension shifts dynamically scaling layouts disproportionately.
    val sidePadding = windowClass.screenHorizontalPadding
    val middleSpacing = 24.dp
    val screenHeightDp = windowClass.screenHeightDp

    // Tablet vertical spacing (To reserve 10% display margins at top and bottom boundaries)
    val topPadding = screenHeightDp * 0.1f
    val bottomPadding = screenHeightDp * 0.1f

    // Screen safe offsets (To avoid content overlap with system notches or navigation bars)
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = sidePadding + systemBarsPadding.calculateStartPadding(layoutDirection)
    val endPadding = sidePadding + systemBarsPadding.calculateEndPadding(layoutDirection)

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = startPadding,
                top = topPadding,
                end = endPadding,
                bottom = bottomPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(middleSpacing)
    ) {
        val swipeThresholdPx = with(density) { 80.dp.toPx() }
        val tabModes = remember {
            listOf(PlayerScreenMode.BOOKMARKS, PlayerScreenMode.SUBTITLES, PlayerScreenMode.RELATED)
        }
        val contentShell = remember(currentMode) {
            when (currentMode) {
                PlayerScreenMode.BOOKMARKS -> PlayerContentShell.Bookmarks
                PlayerScreenMode.RELATED -> PlayerContentShell.Related
                PlayerScreenMode.PLAYER,
                PlayerScreenMode.SUBTITLES -> PlayerContentShell.PlaybackShell
            }
        }

        // ==========================================
        // 1. Left side tab content column (Ratio 1f)
        // ==========================================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // Title: Standardize column horizontal padding (Use windowClass.screenHorizontalPadding for side margins to align elements consistently)
                // Replaces the dynamic screen width multiplication to avoid runtime compilation errors and visual inconsistency.
                .padding(horizontal = windowClass.screenHorizontalPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(currentMode) {
                        if (currentMode == PlayerScreenMode.PLAYER) return@pointerInput
                        var accumulatedX = 0f
                        var hasSwipeTriggered = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                accumulatedX = 0f
                                hasSwipeTriggered = false
                            },
                            onDragEnd = {
                                accumulatedX = 0f
                                hasSwipeTriggered = false
                            },
                            onDragCancel = {
                                accumulatedX = 0f
                                hasSwipeTriggered = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (!hasSwipeTriggered) {
                                    accumulatedX += dragAmount
                                    if (kotlin.math.abs(accumulatedX) > swipeThresholdPx) {
                                        val currentIndex = tabModes.indexOf(currentMode)
                                        val nextMode = if (accumulatedX < 0) {
                                            if (currentIndex < tabModes.lastIndex) tabModes[currentIndex + 1]
                                            else PlayerScreenMode.PLAYER
                                        } else {
                                            if (currentIndex > 0) tabModes[currentIndex - 1]
                                            else PlayerScreenMode.PLAYER
                                        }
                                        onModeChange(nextMode)
                                        hasSwipeTriggered = true
                                    }
                                }
                                change.consume()
                            }
                        )
                    }
            ) {
                // Tab panel crossfades (To animate active contents transitions smoothly)
                AnimatedContent(
                    targetState = contentShell,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        // Decide Transition Spec: Applies fade-in/fade-out for PLAYER (-1) transitions, and horizontal sliding for others.
                        val isPlayerTransition = (currentMode == PlayerScreenMode.PLAYER || prevMode == PlayerScreenMode.PLAYER)
                        if (isPlayerTransition) {
                            (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)))
                                .using(SizeTransform(clip = false))
                        } else {
                            if (targetState.index > initialState.index) {
                                (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(animationSpec = tween(300)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(animationSpec = tween(300)))
                            } else {
                                (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(animationSpec = tween(300)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(300)))
                            }.using(SizeTransform(clip = false))
                        }
                    },
                    label = "player_mode_transition"
                ) { shell ->
                    when (shell) {
                        PlayerContentShell.PlaybackShell -> {
                            // Sync Playback Mode State: Tracks and locks the active playback sub-view (subtitles or player artwork cover).
                            // This state only updates when currentMode is SUBTITLES or PLAYER, freezing the view during exit transitions to other content shells.
                            var lastPlaybackMode by remember { mutableStateOf(currentMode) }
                            if (currentMode == PlayerScreenMode.PLAYER || currentMode == PlayerScreenMode.SUBTITLES) {
                                lastPlaybackMode = currentMode
                            }
                            
                            AnimatedContent(
                                targetState = lastPlaybackMode,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)))
                                        .using(SizeTransform(clip = false))
                                },
                                label = "player_playback_top_transition"
                            ) { topMode ->
                                when (topMode) {
                                    PlayerScreenMode.SUBTITLES -> {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            // Subtitles view layout (To render parsed lyrics elements using stateless components)
                                            SubtitlesView(
                                                subtitles = metadata.subtitles,
                                                currentPosition = currentPosition,
                                                onSeek = { actions.playback.onSeek(it, true) },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    else -> {
                                        // Tablet main cover (To render original high-definition cover artwork in left column)
                                        PlayerCover(
                                            bookId = metadata.id,
                                            isWideScreen = windowClass.isWideScreen,
                                            coverPath = CoverImageSourceSelector.main(
                                                coverPath = metadata.coverPath,
                                                thumbnailPath = metadata.thumbnailPath
                                            ),
                                            isPlaying = isPlaying,
                                            coverLastUpdated = metadata.coverLastUpdated,
                                            coverScene = "player-main-cover",
                                            onAdjustVolume = { actions.playback.onAdjustVolume(it) },
                                            onNextChapter = { actions.playback.onNextChapter() },
                                            onPreviousChapter = { actions.playback.onPreviousChapter() },
                                            onColorExtracted = onColorExtracted
                                        )
                                    }
                                }
                            }
                        }
                        PlayerContentShell.Bookmarks -> {
                            // Bookmark list layout (To delegate custom bookmark lists to stateless component)
                            Box(modifier = Modifier.fillMaxSize()) {
                                BookmarkListView(
                                    bookmarks = metadata.bookmarks,
                                    bookmarkToDelete = bookmarkToDelete,
                                    bookmarkToEdit = bookmarkToEdit,
                                    bookmarkEditTitle = bookmarkEditTitle,
                                    onBookmarkClick = { pos -> actions.playback.onSeek(pos, true) },
                                    onRequestDelete = onRequestDeleteBookmark,
                                    onRequestEdit = onRequestEditBookmark,
                                    onEditTitleChange = onBookmarkEditTitleChange,
                                    onConfirmDelete = onConfirmDeleteBookmark,
                                    onConfirmUpdate = onConfirmUpdateBookmark,
                                    onDismissDialogs = onDismissBookmarkDialogs,
                                    // Bookmark Dialog Haze Routing (Reuse the stable player floating source)
                                    // Edit/delete bookmark dialogs share chapterSheetHazeState with the chapter sheet and player chrome to avoid source rebinding.
                                    hazeState = chapterSheetHazeState,
                                    glassEffectMode = glassEffectMode,
                                    currentPosition = currentPosition,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        PlayerContentShell.Related -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                RelatedBooksView(
                                    currentBookId = metadata.id,
                                    heuristicBooks = fullUiState.heuristicRecommendedBooks,
                                    authorSections = fullUiState.relatedAuthorSections,
                                    narratorSections = fullUiState.relatedNarratorSections,
                                    recentBooks = fullUiState.recentlyAddedBooks,
                                    onBookClick = actions.content.onLoadRelatedBook
                                )
                            }
                        }
                    }
                }
            }

            // Bottom navigation tabs (To coordinate active tab state in left column)
            BottomNavTabs(
                selectedTab = currentMode,
                onTabSelected = {
                    val nextMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                    onModeChange(nextMode)
                }
            )
        }

        // ==========================================
        // 2. Right side tablet controls & title header (Ratio 1f)
        // ==========================================
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = Color.Transparent,
            border = null
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Tablet layout header (To present main title labels and minimizer actions in right column)
                PlayerLandscapeHeader(
                    metadata = metadata,
                    settings = settings,
                    actions = actions,
                    glassEffectMode = glassEffectMode,
                    hazeState = chapterSheetHazeState
                )

                // Push control panel down (To align controls at bottom boundaries)
                Spacer(modifier = Modifier.weight(1f))
                
                // Tablet playback controls (To render timeline markers and speed values)
                PlayerControlPanel(
                    currentPosition = currentPosition,
                    bufferedPosition = bufferedPosition,
                    totalDuration = totalDuration,
                    isChapterMode = isChapterMode,
                    currentChapter = currentChapter,
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                    isSpeedManualMode = isSpeedManualMode,
                    metadata = metadata,
                    settings = settings,
                    actions = actions,
                    buttonColor = animatedBgColor,
                    glassEffectMode = glassEffectMode,
                    hazeState = chapterSheetHazeState,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
