package com.viel.aplayer.ui.player

import android.view.RoundedCorner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.R
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.BlurSnackbar
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.layout.AppWindowSizeClass
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.APlayerTheme
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    // Glass effect mode (To customize overlay blur styles)
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Setup Haze State Parameter (Map fullPageBackdrop parameter to HazeState)
    // Accept a nullable parent/global HazeState to support popup dialog/sheet blur.
    hazeState: HazeState? = null,
    // Dynamic Cover Color (Propagate dynamic cover color for backdrop blending)
    // Accepts the active cover color extracted by the page CoverBackground.
    coverColor: Color?,
    // Color Extracted Callback (Notify parent overlay about extracted cover color)
    // Callback triggered when CoverBackground extracts a dominant color from its software backdrop image.
    onColorExtracted: (Color) -> Unit,
    // Player Floating Surface Ownership (Allow outer overlays to render modal surfaces outside the sampled content tree)
    // PlayerOverlay disables this flag so chapter sheets and bookmark dialogs are composed as siblings of the player hazeSource instead of inside PlayerScreen.
    renderFloatingSurfaces: Boolean = true,
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    // Localized Seek Undo Banner Copy (Resolve the undo action and transient seek message through resources)
    // The banner reflects app-authored playback feedback, while the target position itself remains runtime playback state.
    val seekUndoActionText = stringResource(R.string.player_seek_undo_action)
    val seekUndoMessageText = stringResource(R.string.player_seek_undo_message)

    // =====================================================================
    // Player Container State Gathering (To ensure layout classes remain stateless)
    // Facilitates UI channel adaptation, decoupling, and automated views testing.
    // =====================================================================
    val playbackProgressState = if (isPreview) {
        remember {
            MutableStateFlow(
                PlaybackProgressViewState(
                    elapsedMs = 120000L,
                    bufferedMs = 220000L,
                    durationMs = 360000L,
                    isChapterProgressMode = false
                )
            )
        }
    } else {
        playbackViewModel.playbackProgressState
    }

    val currentChapter = if (isPreview) {
        PlayerChapterItem(
            id = "chapter_1",
            bookId = "book_1",
            bookFileId = "file_1",
            index = 1,
            title = "第一章：危机纪元",
            startPositionMs = 0L,
            durationMs = 360000L,
            fileOffsetMs = 0L,
            // Update PlayerScreen to use AudiobookSchema.ChapterSource: Replacing raw string with type-safe ChapterSource.EMBEDDED.
            source = AudiobookSchema.ChapterSource.EMBEDDED
        )
    } else {
        playbackViewModel.currentChapterState.collectAsStateWithLifecycle().value
    }

    val bookmarkDialogs = if (isPreview) {
        BookmarkViewModel.BookmarkDialogsState(
            toDelete = null,
            toEdit = null,
            editTitle = ""
        )
    } else {
        bookmarkViewModel.bookmarkDialogs.collectAsStateWithLifecycle().value
    }

    // IDE preview data check (To supply mock parameters under layout previews)
    val metadata = if (isPreview) {
        BookMetadataState(
            id = "book_1",
            title = "三体：黑暗森林",
            author = "刘慈欣",
            narrator = "王明",
            coverPath = null,
            thumbnailPath = null,
            coverLastUpdated = 0L,
            // Deprecated: backgroundColorArgb is removed
            // Player Preview Chapters (Use player-scene projections instead of Room relation models)
            // Preview data mirrors the runtime player boundary and keeps this UI file independent from persistence entities.
            // Update PlayerScreen previews to use AudiobookSchema.ChapterSource: Replacing raw string with type-safe ChapterSource.EMBEDDED.
            chapters = listOf(
                PlayerChapterItem("ch_1", "book_1", "file_1", 1, "引子", 0L, 180000L, 0L, AudiobookSchema.ChapterSource.EMBEDDED),
                PlayerChapterItem("ch_2", "book_1", "file_1", 2, "第一章：危机纪元", 180000L, 360000L, 180000L, AudiobookSchema.ChapterSource.EMBEDDED)
            )
        )
    } else {
        playbackViewModel.metadataState.collectAsStateWithLifecycle().value
    }
    // Lower resolution backdrop image (To decouple backdrop drawing parameters from high-res main artwork)
    val playerBackdropCoverPath = CoverImageSourceSelector.backdrop(
        thumbnailPath = metadata.thumbnailPath,
        coverPath = metadata.coverPath
    )

    val settings = if (isPreview) {
        PlayerSettingsState(
            isFullPlayerVisible = true,
            selectedContentTab = -1,
            isChapterProgressMode = false,
            showUndoSeek = false,
            selectedSleepTimer = 0
        )
    } else {
        settingsViewModel.settingsState.collectAsStateWithLifecycle().value
    }

    val controls = if (isPreview) {
        PlaybackViewModel.PlaybackControlState(
            isPlaying = true,
            playbackSpeed = 1.0f,
            isSpeedManualMode = false
        )
    } else {
        playbackViewModel.playbackControlState.collectAsStateWithLifecycle().value
    }
    
    val fullUiState = if (isPreview) {
        PlayerUiState()
    } else {
        playbackViewModel.uiState.collectAsStateWithLifecycle().value
    }

    val targetMode = remember(settings.selectedContentTab) {
        when(settings.selectedContentTab) {
            0 -> PlayerScreenMode.BOOKMARKS
            1 -> PlayerScreenMode.SUBTITLES
            2 -> PlayerScreenMode.RELATED
            else -> PlayerScreenMode.PLAYER
        }
    }

    var currentMode by remember { mutableStateOf(targetMode) }

    // Predict back gesture tracking (To store gesture activation status and percentage values)
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

    // Determine screen orientations (To layout adaptive panels using window class attributes)
    val windowClass = LocalAppWindowSizeClass.current
    val isLandscape = windowClass.isLandscape

    LaunchedEffect(targetMode) {
        currentMode = targetMode
    }

    // System theme synchronization (To align player styling options with active dark/light parameters)
    // Pass LocalDarkTheme value into theme container wrapper to bypass default system dark mode detection.
        // Keep local dark theme (Use current theme value to prevent APlayerTheme from overriding the book-cover dynamic color scheme)
        CompositionLocalProvider(
            com.viel.aplayer.ui.common.theme.LocalDarkTheme provides com.viel.aplayer.ui.common.theme.LocalDarkTheme.current
        ) {
        val focusManager = LocalFocusManager.current
        // Setup Haze State (Initialize local state for player backdrop blur)
        // Splits blurred cover backgrounds into a separate visual sibling layer.
        val coverHazeState = remember { HazeState() }
        // Player Floating Haze Source (Prefer the stable app-level sampler for player floating surfaces)
        // CoverHazeState still owns the decorative cover blur, while chapter sheets, dialogs, dropdowns, and controls sample the player backdrop through the external HazeState when available.
        val externalFloatingHazeState = hazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
        val floatingHazeState = externalFloatingHazeState ?: coverHazeState
        
        // Sync Player Haze State: Initialize a separate HazeState to sample the entire player (including foreground controls).
        // This avoids recursive rendering loops and lets the snackbar blur everything behind it.
        val playerHazeState = remember { HazeState() }



        // Panel sheet back handler (To slide back to player main view using system back gesture)
        PredictiveBackHandler(enabled = currentMode != PlayerScreenMode.PLAYER) { progressFlow ->
            try {
                progressFlow.collect { }
                currentMode = PlayerScreenMode.PLAYER
            } catch (_: CancellationException) {
                // Handle swipe cancel actions (To retain active tab modes when back gestures are aborted)
            }
        }

        // Player minimalization back handler (To minimize full-screen layouts using system swipe gesture)
        PredictiveBackHandler(
            enabled = currentMode == PlayerScreenMode.PLAYER && settings.isFullPlayerVisible
        ) { progressFlow ->
            try {
                // Accumulate drag events (To slide full-screen card down matching predictive gesture progress)
                progressFlow.collect { backEvent ->
                    isPlayerBackActive = true
                    playerBackProgress = backEvent.progress
                }
                actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
                navigationActions.onMinimize()
            } catch (_: CancellationException) {
                // Drag abort handle (To restore original position when swipe gesture is cancelled)
            } finally {
                // Reset gesture tracking (To wipe gesture progress state variables)
                isPlayerBackActive = false
                playerBackProgress = 0f
            }
        }

        val fallbackColor = MaterialTheme.colorScheme.primaryContainer
        val finalCoverColor = coverColor ?: fallbackColor
        // Animated color transitions (To blend cover dominant color gradients smoothly)
        val animatedBgColor by animateColorAsState(
            targetValue = finalCoverColor,
            animationSpec = tween(300),
            label = "bg_color"
        )
        val bgColor = MaterialTheme.colorScheme.background

        val sharedTransitionScope = LocalSharedTransitionScope.current
        val mini2PlayerTargetScope = LocalMini2PlayerTargetScope.current

        // Determine starting corner radius depending on widescreen pill style versus standard bottom bar style
        val startCornerRadius = if (windowClass.isWideScreen) 100.dp else 0.dp
        val endCornerRadius = if (isLandscape) 0.dp else cornerRadiusDp

        /*
         * Outer Card Corner Radius Transition (Dynamic bounds shape interpolation)
         * Transition the outer card corner radius from the compact layout (Pill: 100.dp / Bar: 0.dp)
         * up to the target full screen's corner radius, preventing straight corner overflow.
         */
        // Align transition durations: Set full bounds corner radius transition spec to 300ms to align with other page transitions.
        val animatedCornerRadius by mini2PlayerTargetScope?.transition?.animateDp(
            label = "full_bounds_corner_radius",
            transitionSpec = { tween(300) }
        ) { enterExitState ->
            if (enterExitState == EnterExitState.Visible) endCornerRadius else startCornerRadius
        }
            ?: remember(endCornerRadius) { mutableStateOf(endCornerRadius) }

        // Conditional Bounds Transition: Only apply shared bounds morphing on widescreen/tablet layouts.
        // This ensures the player sheet on standard phones slides up/down cleanly without morphing the background, while still allowing the cover shared element to animate.
        val boundsModifier = if (sharedTransitionScope != null && mini2PlayerTargetScope != null && windowClass.isWideScreen) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    /*
                     * Full Player Bounds Key (Centralized shared bounds identity)
                     *
                     * Resolves the full-player surface key through SharedElementKeys so it stays
                     * aligned with compact bottom-bar player bounds without duplicating key strings.
                     */
                    sharedContentState = rememberSharedContentState(key = SharedElementKeys.playerBounds()),
                    animatedVisibilityScope = mini2PlayerTargetScope,
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(animatedCornerRadius))
                )
            }
        } else {
            Modifier
        }

        // Exit translation range (To map drag offset parameters into downward exit bounds)
        val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

        // Define player surface shape (To prevent clipping contents inside rotated display bounds)
        val playerSurfaceShape = if (isLandscape) {
            RectangleShape
        } else {
            RoundedCornerShape(topStart = animatedCornerRadius, topEnd = animatedCornerRadius)
        }
        // Player Screen Trace State (Describe active player layout without logging track identity)
        // Mode, visibility, playback, orientation, and chapter count identify redraw causes while keeping media details private.
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
                    // Translate cards vertically (To animate card position downwards matching predictive gestures)
                    if (isPlayerBackActive) {
                        translationY = playerBackProgress * maxPredictiveTranslationY
                        alpha = 1f - playerBackProgress * 0.3f
                    }
                }
                .clip(playerSurfaceShape),
            // Ground background bounds (To avoid transparent layout leakage during transition)
            color = bgColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Main Content Container: Wraps all background and foreground elements to sample them as a single sibling node.
                // This prevents recursive rendering loops (feedback loops) when the snackbar applies hazeEffect.
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
                // Mitigate render-thread crash (To resolve background sampling loop errors on specific OPLUS devices)
                // Separates blurred backdrop sampling layers and forefront components as siblings.

                // 1. Pure backdrop layer (Sibling background node)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (externalFloatingHazeState != null) {
                                // Player Backdrop App-Level Source (Register only the non-interactive backdrop for floating glass)
                                // Controls and modal surfaces consume this HazeState as siblings, preventing the app-level sampler from capturing its own glass effects.
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

                // 2. Forefront controls layer (Sibling forefront node)
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Layout resolution dispatch (To load different screen layout templates matching display shapes)
                    val isTabletLandscape = windowClass.isLandscapeTablet
                    when {
                        isTabletLandscape -> {
                            PlayerLandscapeTablet(
                                playbackProgressState = playbackProgressState,
                                currentChapter = currentChapter,
                                // Parameter Alignment (Aligns parameter name with isPlaying of layout templates)
                                // Renamed playing to isPlaying to match defined function arguments.
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
                                chapterSheetHazeState = floatingHazeState
                            )
                        }
                        isLandscape -> {
                            PlayerLandscapePhone(
                                playbackProgressState = playbackProgressState,
                                currentChapter = currentChapter,
                                // Parameter Alignment (Aligns parameter name with isPlaying of layout templates)
                                // Renamed playing to isPlaying to match defined function arguments.
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
                                chapterSheetHazeState = floatingHazeState
                            )
                        }
                        else -> {
                            PlayerPortrait(
                                playbackProgressState = playbackProgressState,
                                currentChapter = currentChapter,
                                // Parameter Alignment (Aligns parameter name with isPlaying of layout templates)
                                // Renamed playing to isPlaying to match defined function arguments.
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
                                navigationActions = navigationActions
                            )
                        }
                    }
                }
            }

                // Blur-supported seek undo banner (To alert users about coordinate rewind options)
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
                        // Offset snackbar margins (To avoid overlapping seek undo bar over bottom controls area)
                        .padding(horizontal = 16.dp, vertical = 96.dp)
                ) {
                    // Render blur snackbar (To support blur sampling overlays under Haze styles)
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
            // Inline Player Floating Surface Host (Preserve standalone PlayerScreen behavior for previews and isolated tests)
            // App shell callers can disable this branch and render the same host outside the page hazeSource to prevent nested modal sampling.
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
    // Chapter Sheet State Ownership (Keep modal sheet mechanics with the floating surface host)
    // Moving the sheet state here lets PlayerOverlay render the chapter sheet as a sibling of sampled player content without duplicating sheet wiring.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Compact Chapter List Surface (Present track index selections through the shared floating surface host)
    // The hazeState is supplied by the caller so app-level overlays can sample a stable source while standalone screens fall back to their local sampler.
    PlaybackPositionChapterListSheetStateful(
        playbackProgressState = playbackProgressState,
        metadata = metadata,
        settings = settings,
        actions = actions,
        sheetState = sheetState,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode
    )

    // Bookmark Creation Surface (Display bookmark naming controls through the shared floating surface host)
    // Keeping this dialog beside the chapter sheet centralizes player modal ownership and avoids nesting dialogs inside sampled page content.
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

// Suppress VM forwarding checker (To allow instantiating empty ViewModel instances under preview components)
@Suppress("ComposeViewModelForwarding", "ComposeViewModelInjection", "ViewModelConstructorInComposable")
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun PlayerScreenPreview() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as android.app.Application
    APlayerTheme {
        // Portrait phone preview (To verify vertical scroll drawer positioning metrics)
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val mockScope = rememberCoroutineScope()
                PlayerScreen(
                    playbackViewModel = PlaybackViewModel(application, mockScope),
                    bookmarkViewModel = BookmarkViewModel(application, mockScope),
                    settingsViewModel = PlayerSettingsViewModel(application, mockScope),
                    actions = PlayerActions(),
                    navigationActions = PlayerNavigationActions(),
                    glassEffectMode = GlassEffectMode.Material,
                    coverColor = null,
                    onColorExtracted = {}
                )
            }
        }
    }
}

// Suppress VM forwarding checker (To allow instantiating empty ViewModel instances under preview components)
@Suppress("ComposeViewModelForwarding", "ComposeViewModelInjection", "ViewModelConstructorInComposable")
@Preview(showBackground = true, apiLevel = 36, widthDp = 800, heightDp = 480)
@Composable
fun PlayerScreenLandscapePreview() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as android.app.Application
    APlayerTheme {
        // Landscape phone preview (To verify double-column layouts under wider screens)
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.LandscapePhone
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val mockScope = rememberCoroutineScope()
                PlayerScreen(
                    playbackViewModel = PlaybackViewModel(application, mockScope),
                    bookmarkViewModel = BookmarkViewModel(application, mockScope),
                    settingsViewModel = PlayerSettingsViewModel(application, mockScope),
                    actions = PlayerActions(),
                    navigationActions = PlayerNavigationActions(),
                    glassEffectMode = GlassEffectMode.Haze,
                    coverColor = null,
                    onColorExtracted = {}
                )
            }
        }
    }
}
