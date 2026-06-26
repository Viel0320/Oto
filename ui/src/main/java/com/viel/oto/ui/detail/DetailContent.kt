package com.viel.oto.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import com.viel.oto.shared.R
import com.viel.oto.application.download.BookCacheState
import com.viel.oto.application.library.LibraryBookSourceType
import com.viel.oto.application.library.LibraryReadStatus
import com.viel.oto.application.library.detail.DetailBookItem
import com.viel.oto.application.library.detail.DetailSnapshot
import com.viel.oto.shared.model.AppSettings
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.AudiobookActionDialog
import com.viel.oto.ui.common.AudiobookActionDialogBook
import com.viel.oto.ui.common.CoverBackground
import com.viel.oto.ui.common.CoverImageSourceSelector
import com.viel.oto.ui.common.OtoDialogTemplate
import com.viel.oto.ui.common.layout.AppWindowSizeClass
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.detail.components.SelectableTextView
import com.viel.oto.ui.detail.layouts.DetailLandscapePhone
import com.viel.oto.ui.detail.layouts.DetailLandscapeTablet
import com.viel.oto.ui.detail.layouts.DetailPortrait
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Stateless L3 UI Skeleton.
 *
 * DetailContent. at the L3 level.
 * The scaffold owns the transparent top bar while each adaptive detail layout
 * consumes side and bottom safe-drawing insets so system bars are not applied twice.
 * The top bar is limited to navigation and overflow; selected-book cache actions are rendered by the
 * control panel beside playback so compact landscape layouts do not overlap header content.
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
    onUpdateReadStatus: (String, LibraryReadStatus) -> Unit = { _, _ -> },
    onForceRegenerate: (String) -> Unit = {},
    onDeleteBook: (String) -> Unit = {},
    onDownloadBook: (String) -> Unit = {},
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String) -> Unit = {},
    onDeleteDownload: (String) -> Unit = {},
    glassEffectMode: GlassEffectMode,
    fullPageHazeState: HazeState? = null,
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
    val onDownloadActionClick: () -> Unit = {
        val selectedBook = book
        if (selectedBook != null) {
            if (uiState.bookCacheStatus.state == BookCacheState.NONE) {
                onDownloadBook(selectedBook.id)
            } else {
                showDownloadDialog = true
            }
        }
    }

    val backdropCoverPath = CoverImageSourceSelector.backdrop(
        thumbnailPath = book?.thumbnailPath,
        coverPath = book?.coverPath
    )
    val coverHazeState = remember { HazeState() }

    val safeDrawingPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()

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

    androidx.activity.compose.PredictiveBackHandler(enabled = isVisible) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                isPredictiveBackActive = true
                predictiveBackProgress = backEvent.progress
            }
            onBackClick()
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
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
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)),
        color = bgColor
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CoverBackground(
                coverPath = backdropCoverPath,
                lastUpdated = book?.lastScannedAt ?: 0L,
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
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface
                        ),
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
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                val windowClass = LocalAppWindowSizeClass.current
                val isLandscape = windowClass.isLandscape
                val isTabletLandscape = windowClass.isLandscapeTablet

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
                            onDownloadActionClick = onDownloadActionClick,
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
                            onDownloadActionClick = onDownloadActionClick,
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
                            onDownloadActionClick = onDownloadActionClick,
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
        OtoDialogTemplate(
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
                    BookCacheState.LOCAL -> Unit
                }
            }
        )
    }

    if (infoDialogText != null) {
        OtoDialogTemplate(
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
 * Adapt DetailBookItem to the shared audiobook action payload.
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

private const val DETAIL_ACTION_DIALOG_COVER_SCENE = "detail-action-dialog-cover"

private fun com.viel.oto.application.download.BookCacheStatus.dialogTitleRes(): Int =
    when (state) {
        BookCacheState.NONE -> R.string.detail_download_dialog_title_none
        BookCacheState.LOCAL -> R.string.detail_download_dialog_title_completed
        BookCacheState.QUEUED -> R.string.detail_download_dialog_title_queued
        BookCacheState.DOWNLOADING -> R.string.detail_download_dialog_title_downloading
        BookCacheState.PAUSED -> R.string.detail_download_dialog_title_paused
        BookCacheState.COMPLETED -> R.string.detail_download_dialog_title_completed
        BookCacheState.FAILED -> R.string.detail_download_dialog_title_failed
    }

@Composable
private fun com.viel.oto.application.download.BookCacheStatus.dialogBodyText(): String =
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
    OtoTheme {
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            DetailContent(
                uiState = DetailUiState(
                    book = DetailSnapshot(
                        item = DetailBookItem(
                            id = "id",
                            rootId = "preview-root",
                            sourceType = LibraryBookSourceType.SINGLE_AUDIO,
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
                    fullSourcePath = ""
                ),
                onBackClick = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                onColorExtracted = {}
            )
        }
    }
}
