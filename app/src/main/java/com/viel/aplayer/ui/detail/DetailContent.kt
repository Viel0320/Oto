package com.viel.aplayer.ui.detail

// Setup Haze Integration (Import dev.chrisbanes.haze modifiers) Import HazeState and haze modifier for Compose-based blur.
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
import androidx.compose.foundation.layout.ime
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
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
    onMoreClick: () -> Unit = {}, // Callback for clicking the top-right more control button
    onSearchClick: (String) -> Unit = {}, // Callback for clicking a specific tag to search for related books
    glassEffectMode: GlassEffectMode, // Precise control of dynamic switching between Material design and frosted glass Haze mode
    // Setup Haze State Arguments (Map backdrop parameters to HazeState) Changed LayerBackdrop to HazeState.
    fullPageHazeState: HazeState? = null,
    onEditClick: (String) -> Unit = {}, // Callback for clicking to edit book metadata details
) {
    val book = uiState.book?.book
    val isVisible = uiState.isVisible
    val backgroundColorArgb = uiState.backgroundColorArgb
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    
    // Top-right dropdown menu visibility management
    var showMenu by remember { mutableStateOf(false) }
    
    // Setup coverHazeState (Manage detail-specific blur state) Replaced coverBackdrop with coverHazeState.
    val coverHazeState = remember { HazeState() }
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val backdropCoverPath = CoverImageSourceSelector.backdrop(
        thumbnailPath = book?.thumbnailPath,
        coverPath = book?.coverPath
    )

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
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .background(bgColor),
        color = Color.Transparent
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Setup CoverBackground Haze State (Link coverHazeState) Passed coverHazeState.
            CoverBackground(
                coverPath = backdropCoverPath,
                lastUpdated = book?.lastScannedAt ?: 0L,
                backgroundColorArgb = backgroundColorArgb,
                glassEffectMode = glassEffectMode,
                hazeState = coverHazeState
            )

            if (isBlur) {
                // Background Blur Layer (Render dynamic ultra-thin blur on top of clear cover background)
                // Draw a full-screen box configured with hazeChild using ultra-thin style to blur the backdrop.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeEffect(
                            state = coverHazeState,
                            style = HazeMaterials.ultraThin())
                )
            }

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
                                // Setup Dropdown Menu Haze State (Link dropdown menu blur state) Replaced backdrop parameter with hazeState.
                                BlurDropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    hazeState = fullPageHazeState ?: coverHazeState,
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
                val windowClass = LocalWindowClass.current
                val isLandscape = windowClass.isLandscape
                val isTabletLandscape = windowClass.isTabletLandscape

                // Setup SubLayout Haze States (Forward coverHazeState to detail layouts) Replaced detailBackdrop with coverHazeState.
                when {
                    isTabletLandscape -> {
                        DetailTabletLandscape(
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

    if (infoDialogText != null) {
        if (isBlur) {
            // Setup InfoDialog Haze State (Link dialog blur state) Replaced backdrop with hazeState.
            BlurDialog(
                onDismissRequest = {
                    infoDialogText = null
                    infoDialogTitle = null
                },
                hazeState = fullPageHazeState ?: coverHazeState,
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
