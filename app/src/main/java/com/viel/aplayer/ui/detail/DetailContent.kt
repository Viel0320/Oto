package com.viel.aplayer.ui.detail

// Setup MiuixBlur Import (Viewport-Level High Resolution Gaussian Blur)
// Replace the legacy blur library dependency with miuix-blur to achieve high-resolution frosted glass Gaussian blur effects based on the viewport.
// Import various Compose component and state dependencies to construct the stateless book details pure rendering component DetailContent.
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
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
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDialog
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import com.viel.aplayer.ui.detail.components.SelectableTextView
import com.viel.aplayer.ui.detail.layouts.DetailLandscapePhone
import com.viel.aplayer.ui.detail.layouts.DetailPortrait
import com.viel.aplayer.ui.detail.layouts.DetailTabletLandscape
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.roundToInt

/**
 * DetailContent Skeleton (Stateless L3 UI Skeleton)
 *
 * Stateless detail page rendering skeleton (DetailContent) at the L3 level.
 * Follows the Compose three-layer architecture specification, removing all coupled references to ViewModels.
 * Directly receives immutable DetailUiState and pure Lambda callbacks (consistent with the lower-level Layout sub-skeletons,
 * avoiding the round-trip of dismantling flat parameters and repackaging them into DetailUiState), providing an efficient, testable rendering layer for adaptive layouts.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun DetailContent(
    uiState: DetailUiState, // Complete UI state of the detail page (passed end-to-end to avoid round-trip overhead of dismantling flat parameters and reassembling them)
    onBackClick: () -> Unit, // Callback triggered when the back button is clicked or user drags down to dismiss
    modifier: Modifier = Modifier,
    onPlayPressed: () -> Unit = {}, // Pre-listener for physical playback button click debounce status
    onPlayClick: () -> Unit = {}, // Official playback action callback
    onMoreClick: () -> Unit = {}, // Callback for clicking the top-right more control button
    onSearchClick: (String) -> Unit = {}, // Callback for clicking a specific tag to search for related books
    glassEffectMode: GlassEffectMode, // Precise control of dynamic switching between Material design and frosted glass miuix-blur mode
    backdrop: LayerBackdrop? = null, // Shared sampling source from the upper layer
    fullPageBackdrop: LayerBackdrop? = null, // Blur mapping source for full-screen seamless sampling
    onEditClick: (String) -> Unit = {}, // Callback for clicking to edit book metadata details
) {
    // Fix L-10 (Deriving UI Render Fields)
    // Derive fields required for L3 rendering from the complete UI state, replacing the redundant round-trip of repackaging flat parameters into DetailUiState inside this component.
    val book = uiState.book?.book
    val isVisible = uiState.isVisible
    val backgroundColorArgb = uiState.backgroundColorArgb
    // State definition: Predictive back drag physical scale and animation displacement progress
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    
    // Top-right dropdown menu visibility management
    var showMenu by remember { mutableStateOf(false) }
    
    // Dedicated layerBackdrop sampling source for background rendering to prevent recursive blur glitches and segmentation fault crashes
    val coverBackdrop = rememberLayerBackdrop()
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur
    // Detail Background Resolution (Thumbnail Preferred Backdrop)
    // The details page background is only used as a 128px blur sampling source, so the path prefers the thumbnail.
    // The main cover resolution is determined independently by PlayerCover in each layout, avoiding the background layer from mistakenly holding a large main cover image.
    val backdropCoverPath = CoverImageSourceSelector.backdrop(
        thumbnailPath = book?.thumbnailPath,
        coverPath = book?.coverPath
    )

    // Dynamically capture the most precise system status bar and physical safe area size
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()

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
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .background(bgColor),
        color = Color.Transparent
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background frosted glass gradient animation and large image sampling rendering
            CoverBackground(
                coverPath = backdropCoverPath,
                lastUpdated = book?.lastScannedAt ?: 0L,
                backgroundColorArgb = backgroundColorArgb,
                glassEffectMode = glassEffectMode,
                backdrop = coverBackdrop
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
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = stringResource(R.string.more_content_description)
                                    )
                                }
                                BlurDropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    backdrop = fullPageBackdrop ?: coverBackdrop,
                                    glassEffectMode = glassEffectMode
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("修改书籍信息") },
                                        onClick = {
                                            showMenu = false
                                            book?.id?.let { bookId ->
                                                onEditClick(bookId)
                                            }
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        ),
                        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars),
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
                // WindowClass Orientation Adaptation (Determine Adaptation Orientation)
                // Use the unified WindowClass interface to obtain current window orientation and tablet landscape status, removing direct dependency on LocalConfiguration.
                val windowClass = LocalWindowClass.current
                val isLandscape = windowClass.isLandscape
                val isTabletLandscape = windowClass.isTabletLandscape

                when {
                    isTabletLandscape -> {
                        DetailTabletLandscape(
                            book = book,
                            uiState = uiState,
                            padding = padding,
                            safeDrawingPadding = safeDrawingPadding,
                            glassEffectMode = glassEffectMode,
                            detailBackdrop = coverBackdrop,
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
                            detailBackdrop = coverBackdrop,
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
                            glassEffectMode = glassEffectMode,
                            detailBackdrop = coverBackdrop,
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

    if (infoDialogText != null) {
        if (isBlur) {
            BlurDialog(
                onDismissRequest = {
                    infoDialogText = null
                    infoDialogTitle = null
                },
                backdrop = fullPageBackdrop ?: coverBackdrop,
                glassEffectMode = glassEffectMode
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    infoDialogTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    infoDialogText?.let { dialogText ->
                        SelectableTextView(
                            text = dialogText,
                            modifier = Modifier.fillMaxWidth(),
                            textColor = MaterialTheme.colorScheme.onSurface,
                            textSizeSp = 16f,
                            lineSpacingExtraSp = 4f
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                infoDialogText = null
                                infoDialogTitle = null
                            }
                        ) {
                            Text("OK")
                        }
                    }
                }
            }
        } else {
            AlertDialog(
                onDismissRequest = {
                    infoDialogText = null
                    infoDialogTitle = null
                },
                confirmButton = {
                    TextButton(onClick = {
                        infoDialogText = null
                        infoDialogTitle = null
                    }) {
                        Text("OK")
                    }
                },
                title = { infoDialogTitle?.let { Text(it) } },
                text = {
                    infoDialogText?.let { dialogText ->
                        SelectableTextView(
                            text = dialogText,
                            modifier = Modifier.fillMaxWidth(),
                            textColor = MaterialTheme.colorScheme.onSurface,
                            textSizeSp = 16f,
                            lineSpacingExtraSp = 4f
                        )
                    }
                }
            )
        }
    }
}

@Preview(name = "Phone Portrait", showBackground = true, apiLevel = 36)
@Composable
fun DetailContentPortraitPreview() {
    APlayerTheme {
        // Portrait Preview Mocking (Provide Portrait Window Class)
        // Explicitly provide PortraitPhone window preset, ensuring the details page renders a vertical details layout from top to bottom in portrait mode.
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            DetailContent(
                uiState = DetailUiState(
                    book = BookWithProgress(
                        book = BookEntity(
                            id = "id",
                            rootId = "preview-root",
                            sourceType = "SINGLE_AUDIO",
                            title = "In the Megachurch",
                            author = "Ryo Asai",
                            narrator = "Narrator A",
                            totalDurationMs = 36000L,
                            year = "2023",
                            description = "A preview description."
                        ),
                        progress = null
                    ),
                    isVisible = true,
                    isAvailable = true,
                    progressPercent = 45,
                    displayProgressPercent = 45,
                    backgroundColorArgb = AppSettings.DEFAULT_GLASS_EFFECT_MODE.ordinal,
                    fullSourcePath = ""
                ),
                onBackClick = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}

@Preview(
    name = "Phone Landscape",
    showBackground = true,
    device = "spec:width=720dp,height=360dp,orientation=landscape,dpi=440",
    apiLevel = 36
)
@Composable
fun DetailContentLandscapePreview() {
    APlayerTheme {
        // Landscape Preview Mocking (Provide Landscape Window Class)
        // Explicitly provide LandscapePhone window preset to test the adaptively balanced left-and-right column layout on landscape phones.
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.LandscapePhone
        ) {
            DetailContent(
                uiState = DetailUiState(
                    book = BookWithProgress(
                        book = BookEntity(
                            id = "id",
                            rootId = "preview-root",
                            sourceType = "SINGLE_AUDIO",
                            title = "In the Megachurch",
                            author = "Ryo Asai",
                            narrator = "Narrator A",
                            totalDurationMs = 36000L,
                            year = "2023",
                            description = "A preview description."
                        ),
                        progress = null
                    ),
                    isVisible = true,
                    isAvailable = true,
                    progressPercent = 45,
                    displayProgressPercent = 45,
                    backgroundColorArgb = AppSettings.DEFAULT_GLASS_EFFECT_MODE.ordinal,
                    fullSourcePath = ""
                ),
                onBackClick = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}

@Preview(
    name = "Tablet Landscape",
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,orientation=landscape,dpi=240",
    apiLevel = 36
)
@Composable
fun DetailContentTabletLandscapePreview() {
    APlayerTheme {
        // Tablet Landscape Preview Mocking (Provide Tablet Window Class)
        // Explicitly provide TabletLandscape window preset, ensuring the wide-screen renders a premium tablet adaptive double-column layout.
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.TabletLandscape
        ) {
            DetailContent(
                uiState = DetailUiState(
                    book = BookWithProgress(
                        book = BookEntity(
                            id = "id",
                            rootId = "preview-root",
                            sourceType = "SINGLE_AUDIO",
                            title = "In the Megachurch",
                            author = "Ryo Asai",
                            narrator = "Narrator A",
                            totalDurationMs = 36000L,
                            year = "2023",
                            description = "A preview description."
                        ),
                        progress = null
                    ),
                    isVisible = true,
                    isAvailable = true,
                    progressPercent = 45,
                    displayProgressPercent = 45,
                    backgroundColorArgb = AppSettings.DEFAULT_GLASS_EFFECT_MODE.ordinal,
                    fullSourcePath = ""
                ),
                onBackClick = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
