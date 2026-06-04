package com.viel.aplayer.ui.player

import android.view.RoundedCorner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurSnackbar
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.components.ChapterListSheetStateful
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkDialog
import com.viel.aplayer.ui.player.layouts.PlayerLandscapePhone
import com.viel.aplayer.ui.player.layouts.PlayerPortrait
import com.viel.aplayer.ui.player.layouts.PlayerTabletLandscape
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

enum class PlayerScreenMode(val index: Int) {
    PLAYER(-1),
    BOOKMARKS(0),
    SUBTITLES(1),
    RELATED(2)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    // Glass effect mode (To customize overlay blur styles)
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Fullpage backdrop sampling (To avoid sub-dialog leakage when rendering blurs)
    fullPageBackdrop: LayerBackdrop? = null,
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current

    // =====================================================================
    // L2 container state gathering (To ensure layout classes remain stateless)
    // Facilitates UI channel adaptation, decoupling, and automated views testing.
    // =====================================================================
    val progressState = if (isPreview) {
        PlayerViewModel.PlaybackProgressViewState(
            elapsedMs = 120000L,
            durationMs = 360000L,
            isChapterProgressMode = false
        )
    } else {
        viewModel.playbackProgressState.collectAsStateWithLifecycle().value
    }

    val currentChapter = if (isPreview) {
        com.viel.aplayer.data.entity.ChapterEntity(
            id = "chapter_1",
            bookId = "book_1",
            bookFileId = "file_1",
            index = 1,
            title = "第一章：危机纪元",
            startPositionMs = 0L,
            durationMs = 360000L,
            fileOffsetMs = 0L,
            source = "EMBEDDED"
        )
    } else {
        viewModel.currentChapterState.collectAsStateWithLifecycle().value
    }

    val bookmarkDialogs = if (isPreview) {
        PlayerViewModel.BookmarkDialogsState(
            toDelete = null,
            toEdit = null,
            editTitle = ""
        )
    } else {
        viewModel.bookmarkDialogs.collectAsStateWithLifecycle().value
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
            backgroundColorArgb = "#FF1E293B".toColorInt(),
            // Wrapped in ChapterWithBookFile relation model (To align chapter schemas with Room relation setups)
            chapters = listOf(
                com.viel.aplayer.data.entity.ChapterWithBookFile(
                    chapter = com.viel.aplayer.data.entity.ChapterEntity("ch_1", "book_1", "file_1", 1, "引子", 0L, 180000L, 0L, "EMBEDDED"),
                    bookFile = null
                ),
                com.viel.aplayer.data.entity.ChapterWithBookFile(
                    chapter = com.viel.aplayer.data.entity.ChapterEntity("ch_2", "book_1", "file_1", 2, "第一章：危机纪元", 180000L, 360000L, 180000L, "EMBEDDED"),
                    bookFile = null
                )
            )
        )
    } else {
        viewModel.metadataState.collectAsStateWithLifecycle().value
    }
    // Lower resolution backdrop image (To decouple backdrop drawing parameters from high-res main artwork)
    val playerBackdropCoverPath = CoverImageSourceSelector.backdrop(
        thumbnailPath = metadata.thumbnailPath,
        coverPath = metadata.coverPath
    )

    val settings = if (isPreview) {
        com.viel.aplayer.ui.settings.PlayerSettingsState(
            isFullPlayerVisible = true,
            selectedContentTab = -1,
            isChapterProgressMode = false,
            showUndoSeek = false,
            selectedSleepTimer = 0
        )
    } else {
        viewModel.settingsState.collectAsStateWithLifecycle().value
    }

    val controls = if (isPreview) {
        PlayerViewModel.PlaybackControlState(
            isPlaying = true,
            playbackSpeed = 1.0f,
            isSpeedManualMode = false
        )
    } else {
        viewModel.playbackControlState.collectAsStateWithLifecycle().value
    }
    
    val fullUiState = if (isPreview) {
        PlayerUiState()
    } else {
        viewModel.uiState.collectAsStateWithLifecycle().value
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
    val windowClass = LocalWindowClass.current
    val isLandscape = windowClass.isLandscape

    LaunchedEffect(targetMode) {
        currentMode = targetMode
    }

    // System theme synchronization (To align player styling options with active dark/light parameters)
    APlayerTheme {
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        
        // Isolate backdrop sampling source (To avoid recursive drawing loops on specific vendor devices)
        // Splits blurred cover backgrounds into a separate visual sibling layer.
        val coverBackdrop = rememberLayerBackdrop()

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

        // Animated color transitions (To blend cover dominant color gradients smoothly)
        val animatedBgColor by animateColorAsState(
            targetValue = Color(metadata.backgroundColorArgb),
            animationSpec = tween(300),
            label = "bg_color"
        )
        val bgColor = MaterialTheme.colorScheme.background

        // Exit translation range (To map drag offset parameters into downward exit bounds)
        val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

        // Define player surface shape (To prevent clipping contents inside rotated display bounds)
        val playerSurfaceShape = if (isLandscape) {
            RectangleShape
        } else {
            RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)
        }

        Surface(
            modifier = modifier
                .fillMaxSize()
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
                // Mitigate render-thread crash (To resolve background sampling loop errors on specific OPLUS devices)
                // Separates blurred backdrop sampling layers and forefront components as siblings.

                // 1. Pure backdrop layer (Sibling background node)
                CoverBackground(
                    coverPath = playerBackdropCoverPath,
                    lastUpdated = metadata.coverLastUpdated,
                    backgroundColorArgb = metadata.backgroundColorArgb,
                    glassEffectMode = glassEffectMode,
                    backdrop = coverBackdrop
                )

                // 2. Forefront controls layer (Sibling forefront node)
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Layout resolution dispatch (To load different screen layout templates matching display shapes)
                    val isTabletLandscape = windowClass.isTabletLandscape
                    when {
                        isTabletLandscape -> {
                            PlayerTabletLandscape(
                                currentPosition = progressState.elapsedMs,
                                totalDuration = progressState.durationMs,
                                isChapterMode = progressState.isChapterProgressMode,
                                currentChapter = currentChapter,
                                // Parameter Alignment (Aligns parameter name with isPlaying of layout templates)
                                // Renamed playing to isPlaying to match defined function arguments.
                                isPlaying = controls.isPlaying,
                                playbackSpeed = controls.playbackSpeed,
                                isSpeedManualMode = controls.isSpeedManualMode,
                                bookmarkToDelete = bookmarkDialogs.toDelete,
                                bookmarkToEdit = bookmarkDialogs.toEdit,
                                bookmarkEditTitle = bookmarkDialogs.editTitle,
                                onRequestDeleteBookmark = { viewModel.requestDeleteBookmark(it) },
                                onRequestEditBookmark = { viewModel.requestEditBookmark(it) },
                                onBookmarkEditTitleChange = { viewModel.onBookmarkEditTitleChange(it) },
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
                                onDismissBookmarkDialogs = { viewModel.dismissBookmarkDialogs() },
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
                                chapterSheetBackdrop = coverBackdrop
                            )
                        }
                        isLandscape -> {
                            PlayerLandscapePhone(
                                currentPosition = progressState.elapsedMs,
                                totalDuration = progressState.durationMs,
                                isChapterMode = progressState.isChapterProgressMode,
                                currentChapter = currentChapter,
                                // Parameter Alignment (Aligns parameter name with isPlaying of layout templates)
                                // Renamed playing to isPlaying to match defined function arguments.
                                isPlaying = controls.isPlaying,
                                playbackSpeed = controls.playbackSpeed,
                                isSpeedManualMode = controls.isSpeedManualMode,
                                bookmarkToDelete = bookmarkDialogs.toDelete,
                                bookmarkToEdit = bookmarkDialogs.toEdit,
                                bookmarkEditTitle = bookmarkDialogs.editTitle,
                                onRequestDeleteBookmark = { viewModel.requestDeleteBookmark(it) },
                                onRequestEditBookmark = { viewModel.requestEditBookmark(it) },
                                onBookmarkEditTitleChange = { viewModel.onBookmarkEditTitleChange(it) },
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
                                onDismissBookmarkDialogs = { viewModel.dismissBookmarkDialogs() },
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
                                chapterSheetBackdrop = coverBackdrop
                            )
                        }
                        else -> {
                            PlayerPortrait(
                                currentPosition = progressState.elapsedMs,
                                totalDuration = progressState.durationMs,
                                isChapterMode = progressState.isChapterProgressMode,
                                currentChapter = currentChapter,
                                // Parameter Alignment (Aligns parameter name with isPlaying of layout templates)
                                // Renamed playing to isPlaying to match defined function arguments.
                                isPlaying = controls.isPlaying,
                                playbackSpeed = controls.playbackSpeed,
                                isSpeedManualMode = controls.isSpeedManualMode,
                                bookmarkToDelete = bookmarkDialogs.toDelete,
                                bookmarkToEdit = bookmarkDialogs.toEdit,
                                bookmarkEditTitle = bookmarkDialogs.editTitle,
                                onRequestDeleteBookmark = { viewModel.requestDeleteBookmark(it) },
                                onRequestEditBookmark = { viewModel.requestEditBookmark(it) },
                                onBookmarkEditTitleChange = { viewModel.onBookmarkEditTitleChange(it) },
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
                                onDismissBookmarkDialogs = { viewModel.dismissBookmarkDialogs() },
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
                                chapterSheetBackdrop = coverBackdrop,
                                offsetY = offsetY,
                                scope = scope,
                                dismissThreshold = dismissThreshold,
                                focusManager = focusManager,
                                navigationActions = navigationActions
                            )
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
                    // Render blur snackbar (To support blur sampling overlays under miuix-blur styles)
                    BlurSnackbar(
                        backdrop = coverBackdrop,
                        glassEffectMode = glassEffectMode,
                        action = {
                            TextButton(onClick = actions.playback.onUndoSeek) {
                                Text(
                                    text = "Undo",
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
                            text = "Jumped to a new position",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Compact chapter list overlays (To present track index selections dynamically)
        ChapterListSheetStateful(
            currentPosition = progressState.elapsedMs,
            totalDuration = progressState.durationMs,
            metadata = metadata,
            settings = settings,
            actions = actions,
            sheetState = sheetState,
            // Full-screen backdrop resolution (To apply full-screen backdrop sampling parameters)
            backdrop = fullPageBackdrop ?: coverBackdrop,
            glassEffectMode = glassEffectMode
        )

        // Bookmark creation sheet (To display input fields for bookmark naming details)
        BookmarkDialog(
            isVisible = settings.isBookmarkDialogVisible,
            defaultTitle = settings.bookmarkTitle,
            onSave = { localTitle ->
                actions.bookmarks.onTitleChange(localTitle)
                actions.bookmarks.onSave()
            },
            onDismiss = actions.bookmarks.onDismissDialog
        )
    }
}

// Suppress VM forwarding checker (To allow instantiating empty ViewModel instances under preview components)
@Suppress("ComposeViewModelForwarding", "ComposeViewModelInjection", "ViewModelConstructorInComposable")
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun PlayerScreenPreview() {
    APlayerTheme {
        // Portrait phone preview (To verify vertical scroll drawer positioning metrics)
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                PlayerScreen(
                    viewModel = PlayerViewModel(),
                    actions = PlayerActions(),
                    navigationActions = PlayerNavigationActions(),
                    glassEffectMode = GlassEffectMode.Material
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
    APlayerTheme {
        // Landscape phone preview (To verify double-column layouts under wider screens)
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.LandscapePhone
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                PlayerScreen(
                    viewModel = PlayerViewModel(),
                    actions = PlayerActions(),
                    navigationActions = PlayerNavigationActions(),
                    glassEffectMode = GlassEffectMode.MiuixBlur
                )
            }
        }
    }
}
