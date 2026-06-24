package com.viel.aplayer.ui.player

import android.view.RoundedCorner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.BlurSnackbar
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.uiPerformanceTrace
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.components.PlaybackPositionChapterListSheetStateful
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkDialog
import com.viel.aplayer.ui.player.layouts.PlayerLandscapePhone
import com.viel.aplayer.ui.player.layouts.PlayerLandscapeTablet
import com.viel.aplayer.ui.player.layouts.PlayerPortrait
import com.viel.aplayer.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

enum class PlayerScreenMode(val index: Int) {
    PLAYER(-1),
    BOOKMARKS(0),
    SUBTITLES(1),
    RELATED(2)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class
)
@Composable
fun PlayerScreen(
    playbackViewModel: PlaybackViewModel,
    bookmarkViewModel: BookmarkViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    coverColor: Color?,
    onColorExtracted: (Color) -> Unit,
    renderFloatingSurfaces: Boolean = true,
    safeDrawingPadding: PaddingValues
) {

    val seekUndoActionText = stringResource(R.string.player_seek_undo_action)
    val seekUndoMessageText = stringResource(R.string.player_seek_undo_message)
    val playbackProgressState = playbackViewModel.playbackProgressState


    val currentChapter = playbackViewModel.currentChapterState.collectAsStateWithLifecycle().value


    val bookmarkDialogs = bookmarkViewModel.bookmarkDialogs.collectAsStateWithLifecycle().value


    val metadata = playbackViewModel.metadataState.collectAsStateWithLifecycle().value

    val playerBackdropCoverPath = CoverImageSourceSelector.backdrop(
        thumbnailPath = metadata.thumbnailPath,
        coverPath = metadata.coverPath
    )

    val settings = settingsViewModel.settingsState.collectAsStateWithLifecycle().value


    val controls = playbackViewModel.playbackControlState.collectAsStateWithLifecycle().value


    val fullUiState = playbackViewModel.uiState.collectAsStateWithLifecycle().value
    val subtitleSyncOffsetMs = playbackViewModel.subtitleSyncOffsetMs.collectAsStateWithLifecycle().value


    val targetMode = remember(settings.selectedContentTab) {
        when(settings.selectedContentTab) {
            0 -> PlayerScreenMode.BOOKMARKS
            1 -> PlayerScreenMode.SUBTITLES
            2 -> PlayerScreenMode.RELATED
            else -> PlayerScreenMode.PLAYER
        }
    }

    var currentMode by remember { mutableStateOf(targetMode) }

    var isPlayerBackActive by remember { mutableStateOf(false) }
    var playerBackProgress by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 50.dp.toPx() }

    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        val corner = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        corner?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    val windowClass = LocalAppWindowSizeClass.current
    val isLandscape = windowClass.isLandscape
    val screenHorizontalPadding = windowClass.screenHorizontalPadding

    LaunchedEffect(targetMode) {
        currentMode = targetMode
    }

        CompositionLocalProvider(
            com.viel.aplayer.ui.common.theme.LocalDarkTheme provides com.viel.aplayer.ui.common.theme.LocalDarkTheme.current
        ) {
        val focusManager = LocalFocusManager.current
        val coverHazeState = remember { HazeState() }
        val externalFloatingHazeState = hazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
        val floatingHazeState = externalFloatingHazeState ?: coverHazeState

        val playerHazeState = remember { HazeState() }



        PredictiveBackHandler(enabled = currentMode != PlayerScreenMode.PLAYER) { progressFlow ->
            try {
                progressFlow.collect { }
                currentMode = PlayerScreenMode.PLAYER
            } catch (_: CancellationException) {
            }
        }

        PredictiveBackHandler(
            enabled = currentMode == PlayerScreenMode.PLAYER && settings.isFullPlayerVisible
        ) { progressFlow ->
            try {
                progressFlow.collect { backEvent ->
                    isPlayerBackActive = true
                    playerBackProgress = backEvent.progress
                }
                actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
                navigationActions.onMinimize()
            } catch (_: CancellationException) {
            } finally {
                isPlayerBackActive = false
                playerBackProgress = 0f
            }
        }

        val fallbackColor = MaterialTheme.colorScheme.primaryContainer
        val finalCoverColor = coverColor ?: fallbackColor
        val animatedBgColor by animateColorAsState(
            targetValue = finalCoverColor,
            animationSpec = tween(300),
            label = "bg_color"
        )
        val bgColor = MaterialTheme.colorScheme.background

        val sharedTransitionScope = LocalSharedTransitionScope.current
        val mini2PlayerTargetScope = LocalMini2PlayerTargetScope.current

        val startCornerRadius = if (windowClass.isWideScreen) 100.dp else 0.dp
        val endCornerRadius = if (isLandscape) 0.dp else cornerRadiusDp

        val animatedCornerRadius by mini2PlayerTargetScope?.transition?.animateDp(
            label = "full_bounds_corner_radius",
            transitionSpec = { tween(300) }
        ) { enterExitState ->
            if (enterExitState == EnterExitState.Visible) endCornerRadius else startCornerRadius
        }
            ?: remember(endCornerRadius) { mutableStateOf(endCornerRadius) }

        val boundsModifier = if (sharedTransitionScope != null && mini2PlayerTargetScope != null) {
            with(sharedTransitionScope) {
                val playerBoundsState = rememberSharedContentState(key = SharedElementKeys.playerBounds())
                if (windowClass.isWideScreen) {
                    Modifier.sharedBounds(
                        sharedContentState = playerBoundsState,
                        animatedVisibilityScope = mini2PlayerTargetScope,
                        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(animatedCornerRadius))
                    )
                } else {
                    /**
                     * Phone compact <-> full morph: the source mini bar is full-width and bottom-flush,
                     * so left/right/bottom already coincide with the full surface. Remeasure the content
                     * against the animated shared bounds instead of scaling the placed layer; the shared
                     * bounds geometry keeps the bottom edge fixed while the tween stays matched to the
                     * corner and cross-fade timing.
                     */
                    Modifier.sharedBounds(
                        sharedContentState = playerBoundsState,
                        animatedVisibilityScope = mini2PlayerTargetScope,
                        boundsTransform = BoundsTransform { _, _ -> tween(durationMillis = 300) },
                        resizeMode = RemeasureToBounds,
                        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(animatedCornerRadius))
                    )
                }
            }
        } else {
            Modifier
        }

        val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

        val playerSurfaceShape = if (isLandscape) {
            RectangleShape
        } else {
            RoundedCornerShape(topStart = animatedCornerRadius, topEnd = animatedCornerRadius)
        }
        val playerScreenTraceState = "mode=$currentMode,full=${settings.isFullPlayerVisible}," +
            "playing=${controls.isPlaying},landscape=$isLandscape,chapters=${metadata.chapters.size}"

        Surface(
            modifier = modifier
                .then(boundsModifier)
                .fillMaxSize()
                .uiPerformanceTrace(
                    node = "PlayerScreen",
                    route = "Player",
                    state = playerScreenTraceState
                )
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .graphicsLayer {
                    if (isPlayerBackActive) {
                        translationY = playerBackProgress * maxPredictiveTranslationY
                        alpha = 1f - playerBackProgress * 0.3f
                    }
                }
                .clip(playerSurfaceShape),
            color = bgColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (glassEffectMode == GlassEffectMode.Haze) {
                                Modifier.hazeSource(playerHazeState)
                            } else {
                                Modifier
                            }
                        )
                ) {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (externalFloatingHazeState != null) {
                                Modifier.hazeSource(externalFloatingHazeState)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    CoverBackground(
                        coverPath = playerBackdropCoverPath,
                        lastUpdated = metadata.coverLastUpdated,
                        coverColor = coverColor,
                        glassEffectMode = glassEffectMode,
                        hazeState = coverHazeState,
                        onColorExtracted = onColorExtracted
                    )
                }

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val isTabletLandscape = windowClass.isLandscapeTablet
                    when {
                        isTabletLandscape -> {
                            PlayerLandscapeTablet(
                                playbackProgressState = playbackProgressState,
                                subtitleSyncOffsetMs = subtitleSyncOffsetMs,
                                currentChapter = currentChapter,
                                isPlaying = controls.isPlaying,
                                playbackSpeed = controls.playbackSpeed,
                                isSpeedManualMode = controls.isSpeedManualMode,
                                bookmarkToDelete = bookmarkDialogs.toDelete,
                                bookmarkToEdit = bookmarkDialogs.toEdit,
                                bookmarkEditTitle = bookmarkDialogs.editTitle,
                                onRequestDeleteBookmark = actions.bookmarks.onRequestDelete,
                                onRequestEditBookmark = actions.bookmarks.onRequestEdit,
                                onBookmarkEditTitleChange = actions.bookmarks.onEditTitleChange,
                                onConfirmDeleteBookmark = {
                                    bookmarkDialogs.toDelete?.let { bookmark ->
                                        actions.bookmarks.onDelete(bookmark)
                                    }
                                },
                                onConfirmUpdateBookmark = {
                                    bookmarkDialogs.toEdit?.let { bookmark ->
                                        actions.bookmarks.onUpdate(bookmark, bookmarkDialogs.editTitle)
                                    }
                                },
                                onDismissBookmarkDialogs = actions.bookmarks.onDismissDialogs,
                                metadata = metadata,
                                settings = settings,
                                actions = actions,
                                fullUiState = fullUiState,
                                currentMode = currentMode,
                                onModeChange = {
                                    currentMode = it
                                    actions.content.onSelectedTabChange(it.index)
                                },
                                animatedBgColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                chapterSheetHazeState = floatingHazeState,
                                safeDrawingPadding = safeDrawingPadding
                            )
                        }
                        isLandscape -> {
                            PlayerLandscapePhone(
                                playbackProgressState = playbackProgressState,
                                subtitleSyncOffsetMs = subtitleSyncOffsetMs,
                                currentChapter = currentChapter,
                                isPlaying = controls.isPlaying,
                                playbackSpeed = controls.playbackSpeed,
                                isSpeedManualMode = controls.isSpeedManualMode,
                                bookmarkToDelete = bookmarkDialogs.toDelete,
                                bookmarkToEdit = bookmarkDialogs.toEdit,
                                bookmarkEditTitle = bookmarkDialogs.editTitle,
                                onRequestDeleteBookmark = actions.bookmarks.onRequestDelete,
                                onRequestEditBookmark = actions.bookmarks.onRequestEdit,
                                onBookmarkEditTitleChange = actions.bookmarks.onEditTitleChange,
                                onConfirmDeleteBookmark = {
                                    bookmarkDialogs.toDelete?.let { bookmark ->
                                        actions.bookmarks.onDelete(bookmark)
                                    }
                                },
                                onConfirmUpdateBookmark = {
                                    bookmarkDialogs.toEdit?.let { bookmark ->
                                        actions.bookmarks.onUpdate(bookmark, bookmarkDialogs.editTitle)
                                    }
                                },
                                onDismissBookmarkDialogs = actions.bookmarks.onDismissDialogs,
                                metadata = metadata,
                                settings = settings,
                                actions = actions,
                                fullUiState = fullUiState,
                                currentMode = currentMode,
                                onModeChange = {
                                    currentMode = it
                                    actions.content.onSelectedTabChange(it.index)
                                },
                                animatedBgColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                chapterSheetHazeState = floatingHazeState,
                                safeDrawingPadding = safeDrawingPadding
                            )
                        }
                        else -> {
                            PlayerPortrait(
                                playbackProgressState = playbackProgressState,
                                subtitleSyncOffsetMs = subtitleSyncOffsetMs,
                                currentChapter = currentChapter,
                                isPlaying = controls.isPlaying,
                                playbackSpeed = controls.playbackSpeed,
                                isSpeedManualMode = controls.isSpeedManualMode,
                                bookmarkToDelete = bookmarkDialogs.toDelete,
                                bookmarkToEdit = bookmarkDialogs.toEdit,
                                bookmarkEditTitle = bookmarkDialogs.editTitle,
                                onRequestDeleteBookmark = actions.bookmarks.onRequestDelete,
                                onRequestEditBookmark = actions.bookmarks.onRequestEdit,
                                onBookmarkEditTitleChange = actions.bookmarks.onEditTitleChange,
                                onConfirmDeleteBookmark = {
                                    bookmarkDialogs.toDelete?.let { bookmark ->
                                        actions.bookmarks.onDelete(bookmark)
                                    }
                                },
                                onConfirmUpdateBookmark = {
                                    bookmarkDialogs.toEdit?.let { bookmark ->
                                        actions.bookmarks.onUpdate(bookmark, bookmarkDialogs.editTitle)
                                    }
                                },
                                onDismissBookmarkDialogs = actions.bookmarks.onDismissDialogs,
                                metadata = metadata,
                                settings = settings,
                                actions = actions,
                                fullUiState = fullUiState,
                                currentMode = currentMode,
                                onModeChange = {
                                    currentMode = it
                                    actions.content.onSelectedTabChange(it.index)
                                },
                                animatedBgColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                chapterSheetHazeState = floatingHazeState,
                                offsetY = offsetY,
                                scope = scope,
                                dismissThreshold = dismissThreshold,
                                focusManager = focusManager,
                                navigationActions = navigationActions,
                                safeDrawingPadding = safeDrawingPadding
                            )
                        }
                    }
                }
            }

                AnimatedVisibility(
                    visible = settings.showUndoSeek,
                    enter = slideInVertically(
                        animationSpec = tween(150),
                        initialOffsetY = { it }
                    ) + fadeIn(animationSpec = tween(150)),
                    exit = slideOutVertically(
                        animationSpec = tween(150),
                        targetOffsetY = { it }
                    ) + fadeOut(animationSpec = tween(150)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = screenHorizontalPadding, vertical = 96.dp)
                ) {
                    BlurSnackbar(
                        hazeState = playerHazeState,
                        glassEffectMode = glassEffectMode,
                        action = {
                            TextButton(onClick = actions.playback.onUndoSeek) {
                                Text(
                                    text = seekUndoActionText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = seekUndoMessageText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if (renderFloatingSurfaces) {
            PlayerFloatingSurfaceHost(
                playbackProgressState = playbackProgressState,
                metadata = metadata,
                settings = settings,
                actions = actions,
                hazeState = floatingHazeState,
                glassEffectMode = glassEffectMode
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerFloatingSurfaceHost(
    playbackProgressState: StateFlow<PlaybackProgressViewState>,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    PlaybackPositionChapterListSheetStateful(
        playbackProgressState = playbackProgressState,
        metadata = metadata,
        settings = settings,
        actions = actions,
        sheetState = sheetState,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode
    )

    BookmarkDialog(
        isVisible = settings.isBookmarkDialogVisible,
        defaultTitle = settings.bookmarkTitle,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        onSave = { localTitle ->
            actions.bookmarks.onTitleChange(localTitle)
            actions.bookmarks.onSave()
        },
        onDismiss = actions.bookmarks.onDismissDialog
    )
}
