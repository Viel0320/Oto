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
import androidx.compose.material3.ButtonDefaults
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
import com.viel.aplayer.application.download.BookCacheState
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.application.library.detail.DetailSnapshot
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.shared.settings.AppSettings
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.AudiobookActionDialog
import com.viel.aplayer.ui.common.AudiobookActionDialogBook
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.layout.AppWindowSizeClass
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.detail.components.DetailTopBarDownloadAction
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
 * The scaffold owns the transparent top bar while each adaptive detail layout
 * consumes side and bottom safe-drawing insets so system bars are not applied twice.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class
)
@Composable
fun DetailContent(
    uiState: DetailUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayPressed: () -> Unit = {},
    onPlayClick: () -> Unit = {},
    onSearchClick: (String) -> Unit = {},
    onEditBook: (String) -> Unit = {},
    onUpdateReadStatus: (String, AudiobookSchema.ReadStatus) -> Unit = { _, _ -> },
    onForceRegenerate: (String) -> Unit = {},
    onDeleteBook: (String) -> Unit = {},
    onDownloadBook: (String) -> Unit = {},
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String) -> Unit = {},
    onDeleteDownload: (String) -> Unit = {},
    glassEffectMode: GlassEffectMode,
    fullPageHazeState: HazeState? = null,
    coverColor: Color?,
    onColorExtracted: (Color) -> Unit,
) {
    val book = uiState.book?.item
    val isVisible = uiState.isVisible
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) } 
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
                hazeState = coverHazeState,
                onColorExtracted = onColorExtracted
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
                                // Local Cache Entry Guard (Hide manual-download buttons for pre-cached/local books)
                                // If the read model projects BookCacheState.LOCAL (SAF roots), the book is already local,
                                // so the offline cache controls are bypassed to prevent user confusion.
                                if (uiState.bookCacheStatus.state != BookCacheState.LOCAL) {
                                    DetailTopBarDownloadAction(
                                        cacheStatus = uiState.bookCacheStatus,
                                        onClick = {
                                            if (uiState.bookCacheStatus.state == BookCacheState.NONE) {
                                                onDownloadBook(book.id)
                                            } else {
                                                showDownloadDialog = true
                                            }
                                        }
                                    )
                                }
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
                // Detail Insets Ownership (Keep scaffold chrome and layout safe areas separate)
                // The TopAppBar consumes status-bar insets, while the adaptive layouts consume
                // physical side and bottom safe-drawing insets from safeDrawingPadding.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                            }
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
                            }
                        )
                    }
                    else -> {
                        DetailPortrait(
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
                            }
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

    if (showDownloadDialog && book != null) {
        val cacheStatus = uiState.bookCacheStatus
        APlayerDialogTemplate(
            onDismissRequest = { showDownloadDialog = false },
            hazeState = fullPageHazeState ?: coverHazeState,
            glassEffectMode = glassEffectMode,
            title = {
                Text(
                    text = stringResource(cacheStatus.dialogTitleRes()),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            body = {
                Text(
                    text = cacheStatus.dialogBodyText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            actions = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
                when (cacheStatus.state) {
                    BookCacheState.QUEUED,
                    BookCacheState.DOWNLOADING -> {
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onPauseDownload(book.id)
                            }
                        ) {
                            Text(stringResource(R.string.detail_download_pause_action))
                        }
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onDeleteDownload(book.id)
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(text = stringResource(R.string.detail_download_cancel_action))
                        }
                    }
                    BookCacheState.PAUSED -> {
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onResumeDownload(book.id)
                            }
                        ) {
                            Text(stringResource(R.string.detail_download_resume_action))
                        }
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onDeleteDownload(book.id)
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(text = stringResource(R.string.detail_download_cancel_action))
                        }
                    }
                    BookCacheState.COMPLETED -> {
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onDeleteDownload(book.id)
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(text = stringResource(R.string.detail_download_delete_action))
                        }
                    }
                    BookCacheState.FAILED -> {
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onDownloadBook(book.id)
                            }
                        ) {
                            Text(stringResource(R.string.detail_download_retry_action))
                        }
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onDeleteDownload(book.id)
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(text = stringResource(R.string.detail_download_delete_action))
                        }
                    }
                    BookCacheState.NONE -> {
                        TextButton(
                            onClick = {
                                showDownloadDialog = false
                                onDownloadBook(book.id)
                            }
                        ) {
                            Text(stringResource(R.string.detail_download_start_action))
                        }
                    }
                    // Local Cache Dialog Guard (Expose no action buttons for local books as a compile-safe fallback)
                    // SAF-based books are natively local, so this dialog should never be triggerable in the UI.
                    BookCacheState.LOCAL -> Unit
                }
            }
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

// Download Dialog Title Mapping (Keep cache-state copy selection close to the detail dialog)
// This preserves one UI translation boundary while the ViewModel continues to expose language-neutral BookCacheStatus values.
private fun com.viel.aplayer.application.download.BookCacheStatus.dialogTitleRes(): Int =
    when (state) {
        BookCacheState.NONE -> R.string.detail_download_dialog_title_none
        // Local Cache Status Fallback (Map local state to completed title res for safety)
        BookCacheState.LOCAL -> R.string.detail_download_dialog_title_completed
        BookCacheState.QUEUED -> R.string.detail_download_dialog_title_queued
        BookCacheState.DOWNLOADING -> R.string.detail_download_dialog_title_downloading
        BookCacheState.PAUSED -> R.string.detail_download_dialog_title_paused
        BookCacheState.COMPLETED -> R.string.detail_download_dialog_title_completed
        BookCacheState.FAILED -> R.string.detail_download_dialog_title_failed
    }

// Download Dialog Body Mapping (Format book-level cache progress without exposing raw metadata rows)
// File counts and percent come from BookCacheStatus, keeping display text independent from Room entity fields.
@Composable
private fun com.viel.aplayer.application.download.BookCacheStatus.dialogBodyText(): String =
    if (state == BookCacheState.NONE || totalFiles == 0) {
        stringResource(R.string.detail_download_dialog_body_none)
    } else {
        stringResource(
            R.string.detail_download_dialog_body_progress,
            progressPercent,
            completedFiles,
            totalFiles
        )
    }

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
