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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.library.vfs.VfsExternalInputReader
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.io.File

/**
 * Edit Book Screen: Stateless Composable layout for modification of audiobook metadata details.
 *
 * Fully decoupled component that receives the targeted `BookEntity` and saves changes via the `onSave` callback.
 * Restricts internal mutable state to fast, local input properties (e.g. text input fields and path values).
 * Supports standard Material 3 color backgrounds and adapts smoothly to glassmorphic visual blurs.
 *
 * @param book The target book entity. Shows a loading indicator if null.
 * @param onNavigationBack Callback invoked when the user exits or cancels editing, ensuring clean-up of temp files.
 * @param onSave Action callback triggered on confirm save, distributing updated parameters and cover paths.
 * @param glassEffectMode Specifies the glassmorphic rendering style.
 * @param modifier The modifier layout chain.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun EditBookScreen(
    book: BookEntity?,
    onNavigationBack: () -> Unit,
    onSave: (title: String, author: String, narrator: String, year: String, description: String, newCoverPath: String?) -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Setup Haze State (Transition backdrop reference to HazeState)
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

    // Blur Feature flag (Verify if Haze settings and state are present)
    val isBlur = glassEffectMode == GlassEffectMode.Haze && detailHazeState != null

    val animatedBgColor = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background

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
                    // Setup EditBook Haze (Apply hazeChild to main sheet container Box)
                    // Remove EditBook Haze Shape (Use default shape matching host) Haze 1.x hazeChild does not take shape parameter.
                    Modifier.hazeEffect(
                        state = detailHazeState,
                        style = HazeMaterials.regular()
                    )
                } else {
                    Modifier
                }
            )
            .background(if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background),
        color = Color.Transparent
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
                                text = "修改书籍信息",
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
                                    contentDescription = "返回"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                },
                containerColor = Color.Transparent
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
                            label = { Text("书名") },
                            placeholder = { Text("请输入书名") },
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
                            label = { Text("作者") },
                            placeholder = { Text("请输入作者") },
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
                            label = { Text("讲述人") },
                            placeholder = { Text("请输入讲述人") },
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
                            label = { Text("年份") },
                            placeholder = { Text("请输入出版年份") },
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
                            label = { Text("简介描述") },
                            placeholder = { Text("请输入书籍简介") },
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
                                    contentDescription = "更换封面"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "更换封面",
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
                                    val finalTitle = title.ifBlank { "Unknown" }
                                    // Trigger save callback, passing all modified metadata properties up to the stateful Overlay container.
                                    onSave(
                                        finalTitle,
                                        author,
                                        narrator,
                                        year,
                                        description,
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
                                            .hazeEffect(
                                                state = detailHazeState,
                                                style = HazeMaterials.regular()
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
                                        contentDescription = "保存"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "保存书籍信息",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        } else {
                            // Fallback Material Button (Graceful degradation to standard opaque component)
                            Button(
                                onClick = {
                                    val finalTitle = title.ifBlank { "Unknown" }
                                    // Trigger save callback, dispatching the complete modified metadata upwards.
                                    onSave(
                                        finalTitle,
                                        author,
                                        narrator,
                                        year,
                                        description,
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
                                        contentDescription = "保存",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "保存书籍信息",
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
                                        coverPath = tempCoverPath ?: book.coverPath ?: book.thumbnailPath,
                                        isPlaying = false,
                                        coverLastUpdated = book.lastScannedAt,
                                        coverScene = "edit-main-cover",
                                        onAdjustVolume = {},
                                        onNextChapter = {},
                                        onPreviousChapter = {},
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(24.dp)),
                                        sizeRatio = 1.0f,
                                        gesturesEnabled = false
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
                                    descriptionField()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    saveButton()
                                }
                            }
                        } else {
                            // Portrait layout (Fails back to vertical stack structure)
                            PlayerCover(
                                coverPath = tempCoverPath ?: book.coverPath ?: book.thumbnailPath,
                                isPlaying = false,
                                coverLastUpdated = book.lastScannedAt,
                                coverScene = "edit-main-cover",
                                onAdjustVolume = {},
                                onNextChapter = {},
                                onPreviousChapter = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp)),
                                sizeRatio = 1.0f,
                                gesturesEnabled = false
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            changeCoverButton()

                            Spacer(modifier = Modifier.height(8.dp))

                            titleField()
                            authorField(Modifier.fillMaxWidth())
                            narratorField(Modifier.fillMaxWidth())
                            yearField(Modifier.fillMaxWidth())
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
                book = BookEntity(
                    id = "preview-id",
                    rootId = "preview-root",
                    sourceType = "SINGLE_AUDIO",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    totalDurationMs = 36000L,
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata."
                ),
                onNavigationBack = {},
                onSave = { _, _, _, _, _, _ -> },
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
                book = BookEntity(
                    id = "preview-id",
                    rootId = "preview-root",
                    sourceType = "SINGLE_AUDIO",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    totalDurationMs = 36000L,
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata."
                ),
                onNavigationBack = {},
                onSave = { _, _, _, _, _, _ -> },
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
                book = BookEntity(
                    id = "preview-id",
                    rootId = "preview-root",
                    sourceType = "SINGLE_AUDIO",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    totalDurationMs = 36000L,
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata."
                ),
                onNavigationBack = {},
                onSave = { _, _, _, _, _, _ -> },
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
