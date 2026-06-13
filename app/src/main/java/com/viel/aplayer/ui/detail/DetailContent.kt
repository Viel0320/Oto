package com.viel.aplayer.ui.detail

// Setup Haze Integration (Import dev.chrisbanes.haze modifiers) Import HazeState and haze modifier for Compose-based blur.
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.application.library.detail.DetailSnapshot
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.AudiobookActionDialog
import com.viel.aplayer.ui.common.AudiobookActionDialogBook
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.layout.AppWindowSizeClass
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.detail.components.SelectableTextView
import com.viel.aplayer.ui.detail.layouts.DetailLandscapePhone
import com.viel.aplayer.ui.detail.layouts.DetailLandscapeTablet
import com.viel.aplayer.ui.detail.layouts.DetailPortrait
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * DetailContent Skeleton (Stateless L3 UI Skeleton)
 *
 * Stateless detail page rendering skeleton (DetailContent) at the L3 level.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class
)
@Composable
fun DetailContent(
    uiState: DetailUiState, // Complete UI state of the detail page
    onBackClick: () -> Unit, // Callback triggered when the back button is clicked or user drags down to dismiss
    modifier: Modifier = Modifier,
    onPlayPressed: () -> Unit = {},
    onPlayClick: () -> Unit = {}, // Official playback action callback
    onSearchClick: (String) -> Unit = {}, // Callback for clicking a specific tag to search for related books
    // Detail Action Edit Command (Forward selected Detail projection id to the app shell)
    // DetailContent owns only the action dialog presentation, while the edit overlay route remains hosted by APlayerApp.
    onEditBook: (String) -> Unit = {},
    // Detail Action Read Status Command (Forward manual read-status changes)
    // The command uses the selected DetailBookItem id so status updates do not depend on Home dialog state.
    // Update Read Status: Update readStatus parameter type to ReadStatus enum.
    onUpdateReadStatus: (String, AudiobookSchema.ReadStatus) -> Unit = { _, _ -> },
    // Detail Action Metadata Refresh Command (Forward forced regeneration from the shared action dialog)
    // DetailContent does not perform library work directly; it only bridges the selected projection id.
    onForceRegenerate: (String) -> Unit = {},
    // Detail Action Delete Command (Forward destructive removal to the app shell)
    // The app shell coordinates playback cleanup, detail dismissal, and library deletion outside the stateless detail renderer.
    onDeleteBook: (String) -> Unit = {},
    glassEffectMode: GlassEffectMode, // Precise control of dynamic switching between Material design and frosted glass Haze mode
    // Detail Floating Dialog Haze Source (Use app-level sampling for detail dialogs)
    // DetailOverlay registers visible Detail content into the stable app source, so floating dialogs avoid local HazeState rebinding.
    fullPageHazeState: HazeState? = null,
    // Dynamic Cover Color (Propagate dynamic cover color for backdrop blending)
    // Accepts the active cover color extracted from Coil bitmap memory.
    coverColor: Color?,
    // Color Extracted Callback (Notify parent overlay about extracted cover color)
    // Callback triggered when Coil successfully loads the cover and extracts its dominant color.
    onColorExtracted: (Color) -> Unit,
) {
    // Detail Render Item (Use the Room-free scene item for every detail layout)
    // The UI reads only Detail-owned fields here, preventing layout parameters from depending on database entities.
    val book = uiState.book?.item
    val isVisible = uiState.isVisible
    // Deprecated: backgroundColorArgb is removed
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    // Detail Action Dialog Visibility (Own only the local modal presentation state)
    // The selected book payload still comes from DetailBookItem, and all mutations are delegated through callbacks.
    var showActionDialog by remember { mutableStateOf(false) }
    // Localized Detail Dialog Action Copy (Resolve the generic acknowledgement button through resources)
    // Info dialog titles and body text are selected book metadata, while the closing action is app-authored UI copy.
    val okActionText = stringResource(R.string.action_ok)

    val backdropCoverPath = CoverImageSourceSelector.backdrop(
        thumbnailPath = book?.thumbnailPath,
        coverPath = book?.coverPath
    )
    // Setup coverHazeState (Manage detail-specific blur state) Replaced coverBackdrop with coverHazeState.
    val coverHazeState = remember { HazeState() }

    // Exclude Keyboard Insets (Avoid Detail Recomposition on IME change)
    // Exclude WindowInsets.ime from safeDrawing to prevent the details page from unnecessary recompositions
    // when the soft keyboard pops up or dismisses inside the overlapping SearchOverlay.
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 50.dp.toPx() }

    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    // Sense system-level interception and draw system predictive back transition animations in real time
    androidx.activity.compose.PredictiveBackHandler(enabled = isVisible) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                isPredictiveBackActive = true
                predictiveBackProgress = backEvent.progress
            }
            onBackClick()
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            // Slide back halfway to abandon the back gesture
        } finally {
            isPredictiveBackActive = false
            predictiveBackProgress = 0f
        }
    }

    val bgColor = MaterialTheme.colorScheme.background
    val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .graphicsLayer {
                // Gesture drag translates downwards without applying scale transformation, making the animation more immersive and stable
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)),
        // Title: Consolidate Surface Background Rendering (Use native color parameter to avoid overdraw)
        // Passes bgColor directly to Surface parameter instead of chaining Modifier.background.
        // This avoids redundant draw calls and allows Compose's Surface layer to optimize color drawing.
        color = bgColor
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Setup CoverBackground Haze State (Link coverHazeState) Passed coverHazeState.
            CoverBackground(
                coverPath = backdropCoverPath,
                lastUpdated = book?.lastScannedAt ?: 0L,
                coverColor = coverColor,
                glassEffectMode = glassEffectMode,
                hazeState = coverHazeState
            )


            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.back_content_description)
                                )
                            }
                        },
                        actions = {
                            // Detail Action Dialog Entry (Open shared audiobook actions from the selected Detail projection)
                            // The top-right control renders only when a DetailBookItem exists, preventing empty overlays from exposing commands without a target book.
                            if (book != null) {
                                IconButton(onClick = { showActionDialog = true }) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = stringResource(R.string.more_content_description)
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            // Detail Top Bar Icon Color Unification (Override Material3 navigation/action defaults)
                            // Material3 uses different defaults for navigation and action icons; forcing onSurface keeps both sides visually consistent.
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        // Exclude Keyboard Insets (Keep Top Bar Stable on IME change)
                        // Also exclude WindowInsets.ime from TopAppBar layout calculations to guarantee absolute header stability.
                        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
                        modifier = Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                    scope.launch {
                                        offsetY.snapTo(newOffset)
                                    }
                                    change.consume()
                                },
                                onDragEnd = {
                                    scope.launch {
                                        if (offsetY.value > dismissThreshold) {
                                            onBackClick()
                                        } else {
                                            offsetY.animateTo(0f, animationSpec = tween(300))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { offsetY.animateTo(0f, animationSpec = tween(300)) }
                                }
                            )
                        }
                    )
                },
                containerColor = Color.Transparent,
            ) { padding ->
                val windowClass = LocalAppWindowSizeClass.current
                val isLandscape = windowClass.isLandscape
                val isTabletLandscape = windowClass.isLandscapeTablet

                // Setup SubLayout Haze States (Forward coverHazeState to detail layouts) Replaced detailBackdrop with coverHazeState.
                when {
                    isTabletLandscape -> {
                        DetailLandscapeTablet(
                            book = book,
                            uiState = uiState,
                            padding = padding,
                            safeDrawingPadding = safeDrawingPadding,
                            glassEffectMode = glassEffectMode,
                            detailHazeState = coverHazeState,
                            onPlayPressed = onPlayPressed,
                            onPlayClick = onPlayClick,
                            onSearchClick = onSearchClick,
                            onShowInfo = { title, text ->
                                infoDialogTitle = title
                                infoDialogText = text
                            },
                            onColorExtracted = onColorExtracted
                        )
                    }
                    isLandscape -> {
                        DetailLandscapePhone(
                            book = book,
                            uiState = uiState,
                            padding = padding,
                            safeDrawingPadding = safeDrawingPadding,
                            glassEffectMode = glassEffectMode,
                            detailHazeState = coverHazeState,
                            onPlayPressed = onPlayPressed,
                            onPlayClick = onPlayClick,
                            onSearchClick = onSearchClick,
                            onShowInfo = { title, text ->
                                infoDialogTitle = title
                                infoDialogText = text
                            },
                            onColorExtracted = onColorExtracted
                        )
                    }
                    else -> {
                        DetailPortrait(
                            book = book,
                            uiState = uiState,
                            padding = padding,
                            glassEffectMode = glassEffectMode,
                            detailHazeState = coverHazeState,
                            onPlayPressed = onPlayPressed,
                            onPlayClick = onPlayClick,
                            onSearchClick = onSearchClick,
                            onShowInfo = { title, text ->
                                infoDialogTitle = title
                                infoDialogText = text
                            },
                            onColorExtracted = onColorExtracted
                        )
                    }
                }
            }
        }
    }

    if (showActionDialog) {
        // Detail Action Dialog Payload (Project the selected Detail item into the shared audiobook action contract)
        // The dialog receives only DetailBookItem-derived data; nullable readStatus is preserved so missing status data leaves every chip unselected.
        AudiobookActionDialog(
            book = book?.toAudiobookActionDialogBook(),
            hazeState = fullPageHazeState ?: coverHazeState,
            glassEffectMode = glassEffectMode,
            coverRequestScene = DETAIL_ACTION_DIALOG_COVER_SCENE,
            onDismissRequest = { showActionDialog = false },
            onEditBook = onEditBook,
            onUpdateReadStatus = onUpdateReadStatus,
            onForceRegenerate = onForceRegenerate,
            onDeleteBook = onDeleteBook
        )
    }

    if (infoDialogText != null) {
        APlayerDialogTemplate(
            onDismissRequest = {
                infoDialogText = null
                infoDialogTitle = null
            },
            hazeState = fullPageHazeState ?: coverHazeState,
            glassEffectMode = glassEffectMode,
            scrollable = true,
            title = {
                infoDialogTitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            body = {
                // Detail Info Dialog Body (Keep selectable metadata text inside the shared dialog shell)
                // The detail page owns the selected text payload while APlayerDialogTemplate owns the common chrome, blur source, and action row.
                infoDialogText?.let { dialogText ->
                    SelectableTextView(
                        text = dialogText,
                        modifier = Modifier.fillMaxWidth(),
                        textColor = MaterialTheme.colorScheme.onSurface,
                        textSizeSp = 16f,
                        lineSpacingExtraSp = 4f
                    )
                }
            },
            actions = {
                TextButton(
                    onClick = {
                        infoDialogText = null
                        infoDialogTitle = null
                    }
                ) {
                    Text(okActionText)
                }
            }
        )
    }
}

/**
 * Detail Action Dialog Projection (Adapt DetailBookItem to the shared audiobook action payload)
 *
 * Keeps the action dialog data source inside the Detail scene projection, including nullable readStatus so callers without status data do not receive a fake selected chip.
 */
private fun DetailBookItem.toAudiobookActionDialogBook(): AudiobookActionDialogBook =
    AudiobookActionDialogBook(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        coverPath = coverPath,
        thumbnailPath = thumbnailPath,
        lastScannedAt = lastScannedAt,
        readStatus = readStatus
    )

// Detail Action Dialog Cover Scene (Preserve cover-cache diagnostics for Detail-origin action dialogs)
// The shared dialog builds the Coil request, while this scene name keeps Detail menu cover loads distinguishable from Home action dialogs.
private const val DETAIL_ACTION_DIALOG_COVER_SCENE = "detail-action-dialog-cover"

@Preview(name = "Phone Portrait", showBackground = true, apiLevel = 36)
@Composable
fun DetailContentPortraitPreview() {
    APlayerTheme {
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            DetailContent(
                uiState = DetailUiState(
                    book = DetailSnapshot(
                        item = DetailBookItem(
                            id = "id",
                            rootId = "preview-root",
                            // Update DetailContent to use AudiobookSchema.SourceType: Replacing raw string with enum.
                            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                            title = "In the Megachurch",
                            author = "Ryo Asai",
                            narrator = "Narrator A",
                            totalDurationMs = 36000L,
                            year = "2023",
                            description = "A preview description.",
                            progressPercent = 45
                        ),
                    ),
                    isVisible = true,
                    isAvailable = true,
                    progressPercent = 45,
                    displayProgressPercent = 45,
                    // Deprecated: backgroundColorArgb is removed
                    fullSourcePath = ""
                ),
                onBackClick = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                coverColor = null,
                onColorExtracted = {}
            )
        }
    }
}
