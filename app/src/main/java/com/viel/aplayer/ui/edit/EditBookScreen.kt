package com.viel.aplayer.ui.edit

// Explicitly import IME window insets to exclude virtual soft-keyboard height from visual layouts, avoiding multiple offset adjustments.
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import com.viel.aplayer.R
import com.viel.aplayer.application.library.edit.EditBookDraft
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.library.vfs.VfsExternalInputReader
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.DynamicColorSchemeHelper
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import dev.chrisbanes.haze.HazeState
import java.io.File

/**
 * Edit Book Screen: Stateless Composable layout for modification of audiobook metadata details.
 *
 * Fully decoupled component that receives the targeted edit draft and saves changes via the `onSave` callback.
 * Restricts internal mutable state to fast, local input properties (e.g. text input fields and path values).
 * Supports standard Material 3 color backgrounds and adapts smoothly to glassmorphic visual blurs.
 *
 * @param book The target edit draft. Shows a loading indicator if null.
 * @param onNavigationBack Callback invoked when the user exits or cancels editing, ensuring clean-up of temp files.
 * @param onSave Action callback triggered on confirm save, distributing updated parameters and cover paths.
 * @param glassEffectMode Specifies the glassmorphic rendering style.
 * @param modifier The modifier layout chain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    book: EditBookDraft?,
    onNavigationBack: () -> Unit,
    // EditBookScreen Save Signature (Adds series parameter to save callback)
    // Passes series name along with other text metadata to the overlay controller.
    onSave: (title: String, author: String, narrator: String, year: String, description: String, series: String, newCoverPath: String?) -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Edit Sheet Haze Source (Receive the stable overlay sampling state)
    // The legacy parameter name is preserved for compatibility, but production callers now pass the app-level HazeState.
    detailHazeState: HazeState? = null
) {
    // Android Context (Needed for file handling and resolution operations)
    val context = LocalContext.current

    // Temp Cover Image Reference (Tracks the path of user-cropped square cover preview)
    var tempCoverPath by remember { mutableStateOf<String?>(null) }

    // Image Picker Contract (Launches photo picker, crops image, and updates temporary cache path)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { inputUri ->
            book?.let { currentBook ->
                val tempFile = File(context.cacheDir, "temp_cover_${currentBook.id}_${System.currentTimeMillis()}.jpg")
                if (cropToSquareAndSave(context, inputUri, tempFile)) {
                    // Disk Cleanup (Purge former temporary assets to avoid cache growth)
                    tempCoverPath?.let { oldPath ->
                        val oldFile = File(oldPath)
                        if (oldFile.exists()) {
                            oldFile.delete()
                        }
                    }
                    tempCoverPath = tempFile.absolutePath
                }
            }
        }
    }

    // Exit handler (Cleans up temporary files on cancel actions and navigates back)
    val handleCancel = remember(tempCoverPath) {
        {
            tempCoverPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            onNavigationBack()
        }
    }

    // Predictive Back State (Observes system gestures to drive exit animations)
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // Predictive Back Interceptor (Consumes progress values, handles file cleaning on pop)
    androidx.activity.compose.PredictiveBackHandler(enabled = book != null) { progressFlow ->
        try {
            // Track gesture swipe progress
            progressFlow.collect { backEvent ->
                isPredictiveBackActive = true
                predictiveBackProgress = backEvent.progress
            }
            handleCancel()
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            // Gesture cancelled mid-swipe
        } finally {
            isPredictiveBackActive = false
            predictiveBackProgress = 0f
        }
    }

    // Lifecycle Guard Disposable (Ensures removal of temporary images upon Composable disposal)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            tempCoverPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    // Edit Cover Display Source (Use the same path for preview artwork and page color extraction)
    // A freshly uploaded cover replaces the book cover immediately, so this key changes and forces the edit page color state to refresh from the new file.
    val editCoverPath = tempCoverPath ?: book?.coverPath ?: book?.thumbnailPath
    // Edit Cover Dynamic Color State (Seed the edit page theme from its own currently displayed cover)
    // The cache gives an instant color when available, while PlayerCover will update this state after Coil decodes a newly uploaded temporary cover.
    var editCoverColor by remember(editCoverPath) {
        mutableStateOf<Color?>(ImageProcessor.getCachedColor(editCoverPath)?.let { Color(it) })
    }
    val darkTheme = LocalDarkTheme.current
    val fallbackColorScheme = MaterialTheme.colorScheme
    // Edit Cover Color Scheme (Derive Material colors from the edit page cover instead of inheriting the underlying page)
    // This keeps text fields, outlines, buttons, and top-bar content aligned with the editable cover, including temporary covers chosen before save.
    val editColorScheme = remember(editCoverColor, darkTheme, fallbackColorScheme) {
        editCoverColor?.let { coverColor ->
            DynamicColorSchemeHelper.generateColorSchemeFromSeed(
                seedColor = coverColor,
                darkTheme = darkTheme,
                fallbackScheme = fallbackColorScheme
            )
        }
    }

    val contentBlock = @Composable {
    // Blur Feature flag (Verify if Haze settings and state are present)
    val isBlur = glassEffectMode == GlassEffectMode.Haze && detailHazeState != null

    val animatedBgColor = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    // Edit Page Content Color (Read foreground color from the edit cover-derived Material scheme)
    // Surface and Scaffold are transparent in Haze mode, so explicit contentColor prevents the old underlying page color from leaking into the edit UI.
    val editContentColor = MaterialTheme.colorScheme.onSurface

    // Background tinting brush (Apply translucency in blur mode to allow backdrop colors to bleed through)
    val backgroundBrush = remember(animatedBgColor, bgColor, isBlur) {
        if (isBlur) {
            Brush.verticalGradient(
                colors = listOf(
                    animatedBgColor.copy(alpha = 0.35f),
                    bgColor.copy(alpha = 0.5f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    animatedBgColor.copy(alpha = 0.9f),
                    bgColor.copy(alpha = 0.95f)
                )
            )
        }
    }

    val density = LocalDensity.current
    val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

    // Window Rounded Corner Extraction (Ensure visual harmony with physical display edges)
    // Queries screen corner radius from system window insets, mapping the container top corners
    // directly to device outer edges for a clean aesthetic.
    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // Predictive back transition (Shift container vertically and apply fade-out relative to progress)
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .then(
                if (isBlur) {
                    // Liquid Glass Edit Sheet (Use custom liquidGlassCompatEffect to apply fluid glass highlight borders on the sheet background) Apply custom glass effect with screen corner profile shape.
                    Modifier.liquidGlassCompatEffect(
                        state = detailHazeState,
                        style = LiquidGlassStyle(shape = RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
                    )
                } else {
                    Modifier
                }
            )
            .background(if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background),
        color = Color.Transparent,
        // Edit Surface Content Color (Bind foreground defaults to the cover-derived scheme)
        // Transparent Material surfaces cannot infer a useful content color, so the edit page supplies its own cover-based foreground.
        contentColor = editContentColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Conditional Background Modifier (Prevent compiler type-inference issues between Color and Brush)
                .then(
                    if (isBlur) {
                        Modifier.background(Color.Transparent)
                    } else {
                        Modifier.background(backgroundBrush)
                    }
                )
        ) {
            Scaffold(
                // Scaffold Insets Configuration (Prevent double soft-keyboard padding compression)
                // Excludes `WindowInsets.ime` from the Scaffold margins to block cumulative padding calculation.
                // This allows the inner container column to request `.imePadding()` individually,
                // solving double-margin squashing bugs where fields would be unreachable during typing.
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
                topBar = {
                    TopAppBar(
                        modifier = Modifier,
                        // Header Insets Override (Prevent titlebar offset displacement during input focus)
                        // Bypasses IME insets for the TopAppBar, keeping the header from shifting upwards
                        // and creating unwanted empty padding gaps when the keyboard populates.
                        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
                        title = {
                            Text(
                                text = stringResource(R.string.edit_book_title),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = handleCancel, // Call handleCancel to clean up temporary images and exit upon navigation back click.
                                modifier = Modifier
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.back_content_description)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            // Edit Top Bar Cover Content Color (Use the edit cover-derived foreground for chrome)
                            // Title and icons share one explicit content color so uploaded cover changes update the whole header consistently.
                            titleContentColor = editContentColor,
                            navigationIconContentColor = editContentColor,
                            actionIconContentColor = editContentColor
                        )
                    )
                },
                containerColor = Color.Transparent,
                // Edit Scaffold Content Color (Propagate cover-derived foreground through the transparent Scaffold)
                // Body text, progress indicators, and default icons inherit the edit cover content color instead of the host page color.
                contentColor = editContentColor
            ) { paddingValues ->
                if (book == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // State isolation (Isolate high-frequency field inputs locally to avoid excessive recomposition costs)
                    var title by remember(book) { mutableStateOf(book.title) }
                    var author by remember(book) { mutableStateOf(book.author) }
                    var narrator by remember(book) { mutableStateOf(book.narrator) }
                    var year by remember(book) { mutableStateOf(book.year) }
                    // EditBookScreen Series State (Local series text value holder)
                    // Holds the editing series name state locally to prevent full screen recompositions.
                    var series by remember(book) { mutableStateOf(book.series) }
                    var description by remember(book) { mutableStateOf(book.description) }

                    // Input styling (Apply translucent background elements under blur mode to enhance visual contrast)
                    val textFieldColors = if (isBlur) {
                        OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }

                    // Responsive layout decision (Check window class parameters to dispatch dual-column grid details)
                    val windowClass = LocalWindowClass.current
                    val useLandscapeLayout = windowClass.isWideScreen

                    // Local input builders (Shared field compositions to avoid duplicating layouts across landscape/portrait)
                    val titleField = @Composable {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(R.string.edit_book_title_label)) },
                            placeholder = { Text(stringResource(R.string.edit_book_title_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val authorField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = author,
                            onValueChange = { author = it },
                            label = { Text(stringResource(R.string.author_label)) },
                            placeholder = { Text(stringResource(R.string.edit_book_author_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = modifier
                        )
                    }

                    val narratorField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = narrator,
                            onValueChange = { narrator = it },
                            label = { Text(stringResource(R.string.narrator_label)) },
                            placeholder = { Text(stringResource(R.string.edit_book_narrator_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = modifier
                        )
                    }

                    val yearField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = year,
                            onValueChange = { year = it },
                            label = { Text(stringResource(R.string.edit_book_year_label)) },
                            placeholder = { Text(stringResource(R.string.edit_book_year_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = modifier
                        )
                    }

                    // EditBookScreen Series Field Composable (OutlinedTextField input for series)
                    // Input component for updating the book's series meta field.
                    val seriesField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = series,
                            onValueChange = { series = it },
                            label = { Text(stringResource(R.string.edit_book_series_label)) },
                            placeholder = { Text(stringResource(R.string.edit_book_series_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = modifier
                        )
                    }

                    val descriptionField = @Composable {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(R.string.edit_book_description_label)) },
                            placeholder = { Text(stringResource(R.string.edit_book_description_placeholder)) },
                            minLines = 4,
                            maxLines = 8,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Reusable cover button (Unified action element for photo retrieval)
                    val changeCoverButton = @Composable {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = stringResource(R.string.edit_book_change_cover)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.edit_book_change_cover),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    val saveButton = @Composable {
                        // Save changes button.
                        if (isBlur) {
                            // Glassmorphic save button (Match details page play button styling)
                            // Utilizes the shared `detailBackdrop` parameter. Applies primary alpha tint (0.12f)
                            // and maps a 1.dp border (0.25f) around the container.
                            Surface(
                                onClick = {
                                    // Edit Save Title Forwarding (Leave title validation to the edit command policy)
                                    // The screen forwards raw input so localized display fallback cannot be persisted as metadata.
                                    // Trigger save callback, passing all modified metadata properties up to the stateful Overlay container.
                                    onSave(
                                        title,
                                        author,
                                        narrator,
                                        year,
                                        description,
                                        series,
                                        tempCoverPath
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    // Apply blur backdrop to save container for design continuity
                                    .then(
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            // Liquid Glass Save Button (Use custom liquidGlassCompatEffect to render the button border with liquid glass highlights) Apply custom glass effect with 16.dp rounding shape.
                                            .liquidGlassCompatEffect(
                                                state = detailHazeState,
                                                style = LiquidGlassStyle(shape = RoundedCornerShape(16.dp))
                                            )
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                ),
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = stringResource(R.string.action_save)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.edit_book_save),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        } else {
                            // Fallback Material Button (Graceful degradation to standard opaque component)
                            Button(
                                onClick = {
                                    // Edit Save Title Forwarding (Leave title validation to the edit command policy)
                                    // The screen forwards raw input so localized display fallback cannot be persisted as metadata.
                                    // Trigger save callback, dispatching the complete modified metadata upwards.
                                    onSave(
                                        title,
                                        author,
                                        narrator,
                                        year,
                                        description,
                                        series,
                                        tempCoverPath
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = stringResource(R.string.action_save),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.edit_book_save),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .consumeWindowInsets(paddingValues)
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            // Spacing adjustment (Safely consume windows insets and apply a clean 24.dp layout margin)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        if (useLandscapeLayout) {
                            // Wide screen mode (Renders dual-pane split structures)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Left pane column: Houses the cover graphic capped at 280dp
                                Column(
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .widthIn(max = 280.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    PlayerCover(
                                        coverPath = editCoverPath,
                                        isPlaying = false,
                                        coverLastUpdated = book.coverLastUpdated,
                                        coverScene = "edit-main-cover",
                                        onAdjustVolume = {},
                                        onNextChapter = {},
                                        onPreviousChapter = {},
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(24.dp)),
                                        sizeRatio = 1.0f,
                                        gesturesEnabled = false,
                                        // Edit Cover Color Extraction (Refresh the edit page theme after the visible cover decodes)
                                        // This callback is also triggered for temporary uploaded covers, so controls recolor before the user saves.
                                        onColorExtracted = { editCoverColor = it }
                                    )

                                    changeCoverButton()
                                }

                                // Right pane column: Stacks metadata input controls and submit button
                                Column(
                                    modifier = Modifier.weight(1.8f),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    titleField()
                                    authorField(Modifier.fillMaxWidth())
                                    narratorField(Modifier.fillMaxWidth())
                                    yearField(Modifier.fillMaxWidth())
                                    // EditBookScreen Series Field Insertion (Render series input in wide layouts)
                                    seriesField(Modifier.fillMaxWidth())
                                    descriptionField()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    saveButton()
                                }
                            }
                        } else {
                            // Portrait layout (Fails back to vertical stack structure)
                            PlayerCover(
                                coverPath = editCoverPath,
                                isPlaying = false,
                                coverLastUpdated = book.coverLastUpdated,
                                coverScene = "edit-main-cover",
                                onAdjustVolume = {},
                                onNextChapter = {},
                                onPreviousChapter = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp)),
                                sizeRatio = 1.0f,
                                gesturesEnabled = false,
                                // Edit Cover Color Extraction (Refresh the edit page theme after the visible cover decodes)
                                // This callback is also triggered for temporary uploaded covers, so controls recolor before the user saves.
                                onColorExtracted = { editCoverColor = it }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            changeCoverButton()

                            Spacer(modifier = Modifier.height(8.dp))

                            titleField()
                            authorField(Modifier.fillMaxWidth())
                            narratorField(Modifier.fillMaxWidth())
                            yearField(Modifier.fillMaxWidth())
                            // EditBookScreen Series Field Insertion (Render series input in portrait layouts)
                            seriesField(Modifier.fillMaxWidth())
                            descriptionField()
                            Spacer(modifier = Modifier.height(16.dp))
                            saveButton()
                        }
                    }
                }
            }
        }
    }
    }

    // Edit Cover Theme Application (Apply the cover-derived color scheme around the full edit surface)
    // The wrapper sits outside transparent Surface and Scaffold layers so contentColor, primary, outline, and button colors all come from the current edit cover.
    if (editColorScheme != null) {
        MaterialTheme(colorScheme = editColorScheme, content = contentBlock)
    } else {
        contentBlock()
    }
}

/**
 * Crop to Square and Save: Decodes, crops, resizes, and writes photo selection.
 *
 * Crops image input to square aspect ratio, resizes it to 800x800, and saves as a 90% JPEG.
 * Built with memory safety features to prevent Native OOM issues and ensure bitmap allocation recycling.
 */
private fun cropToSquareAndSave(
    context: android.content.Context,
    inputUri: android.net.Uri,
    outputFile: File
): Boolean {
    // VFS Input Stream Bridge (Open files using VfsExternalInputReader instead of querying ContentResolver directly)
    val externalInputReader = VfsExternalInputReader(context)
    var inputStream: java.io.InputStream? = null
    try {
        inputStream = externalInputReader.openInputStream(inputUri) ?: return false
        // Decode dimensions (Check bound dimensions beforehand to configure sub-sampling scale and avoid heap OOM)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        // Load bitmap buffer (Initialize fresh stream mapping to decode actual grid values into heap)
        inputStream = externalInputReader.openInputStream(inputUri) ?: return false
        val maxDim = maxOf(options.outWidth, options.outHeight)
        val decodeOptions = android.graphics.BitmapFactory.Options()
        // Resize check (Apply sub-sampling factor of 2 if source dimensions exceed 2000 pixels)
        if (maxDim > 2000) {
            decodeOptions.inSampleSize = 2
        }
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, decodeOptions) ?: return false
        inputStream.close()

        // Perform square crop centered relative to the shortest side
        val width = bitmap.width
        val height = bitmap.height
        val size = minOf(width, height)
        val x = (width - size) / 2
        val y = (height - size) / 2

        val croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, x, y, size, size)

        // Downscale resolution (Resize the cropped square to 800x800 pixels to optimize disk layout and memory profiles)
        val targetResolution = 800
        val finalBitmap = if (size > targetResolution) {
            croppedBitmap.scale(targetResolution, targetResolution)
        } else {
            croppedBitmap
        }

        // Output file commit (Compress and write image block to target file using 90% JPEG quality)
        outputFile.parentFile?.mkdirs()
        java.io.FileOutputStream(outputFile).use { out ->
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }

        // Explicit recycling (Call recycle on bitmaps to free graphics memory immediately)
        if (finalBitmap != croppedBitmap) {
            finalBitmap.recycle()
        }
        if (croppedBitmap != bitmap) {
            croppedBitmap.recycle()
        }
        bitmap.recycle()
        return true
    } catch (e: Exception) {
        android.util.Log.e("EditBookScreen", "居中裁剪正方形封面失败，原因: ", e)
        try { inputStream?.close() } catch (_: Exception) {}
        return false
    }
}

/**
 * Layout Previews: Integrated Compose visualization targets.
 *
 * Verifies component rendering across three typical layouts:
 * 1. Phone Portrait: Standard vertical form.
 * 2. Phone Landscape: Split horizontal form.
 * 3. Tablet Landscape: Dual-pane screen configuration.
 */
@Preview(name = "Phone Portrait", showBackground = true, apiLevel = 36)
@Composable
fun EditBookScreenPortraitPreview() {
    APlayerTheme {
        // Apply PortraitPhone profile configuration
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            EditBookScreen(
                // Preview Edit Draft (Use the edit scene projection instead of a Room entity)
                // This keeps preview data aligned with the runtime UI contract after the edit read model mapping.
                book = EditBookDraft(
                    id = "preview-id",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata.",
                    // Preview Series Field (Define dummy series for previews)
                    series = "preview-series",
                    coverPath = null,
                    thumbnailPath = null,
                    coverLastUpdated = 0L
                ),
                onNavigationBack = {},
                onSave = { _, _, _, _, _, _, _ -> },
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
fun EditBookScreenLandscapePreview() {
    APlayerTheme {
        // Apply LandscapePhone profile configuration
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.LandscapePhone
        ) {
            EditBookScreen(
                // Preview Edit Draft (Use the edit scene projection instead of a Room entity)
                // This keeps landscape previews from depending on persistence-only fields.
                book = EditBookDraft(
                    id = "preview-id",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata.",
                    // Preview Series Field (Define dummy series for previews)
                    series = "preview-series",
                    coverPath = null,
                    thumbnailPath = null,
                    coverLastUpdated = 0L
                ),
                onNavigationBack = {},
                onSave = { _, _, _, _, _, _, _ -> },
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
fun EditBookScreenTabletLandscapePreview() {
    APlayerTheme {
        // Apply TabletLandscape profile configuration
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.TabletLandscape
        ) {
            EditBookScreen(
                // Preview Edit Draft (Use the edit scene projection instead of a Room entity)
                // This keeps tablet previews on the same type consumed by production EditBookScreen.
                book = EditBookDraft(
                    id = "preview-id",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata.",
                    // Preview Series Field (Define dummy series for previews)
                    series = "preview-series",
                    coverPath = null,
                    thumbnailPath = null,
                    coverLastUpdated = 0L
                ),
                onNavigationBack = {},
                onSave = { _, _, _, _, _, _, _ -> },
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
