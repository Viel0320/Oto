package com.viel.oto.ui.edit

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.animateColorScheme
import com.viel.oto.shared.R
import com.viel.oto.application.library.edit.EditBookDraft
import com.viel.oto.media.parser.ImageProcessor
import com.viel.oto.shared.model.AppSettings
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.CoverBackground
import com.viel.oto.ui.common.PlayerCover
import com.viel.oto.ui.common.layout.AppWindowSizeClass
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.LocalAmoled
import com.viel.oto.ui.common.theme.LocalDarkTheme
import com.viel.oto.ui.common.theme.OtoTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun EditBookScreen(
    book: EditBookDraft?,
    onNavigationBack: () -> Unit,
    onSave: (title: String, author: String, narrator: String, year: String, description: String, series: String, newCoverUri: String?) -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    detailHazeState: HazeState? = null
) {

    var selectedCoverUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            selectedCoverUri = uri
        }
    }

    val handleCancel = remember {
        {
            onNavigationBack()
        }
    }

    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    androidx.activity.compose.PredictiveBackHandler(enabled = book != null) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                isPredictiveBackActive = true
                predictiveBackProgress = backEvent.progress
            }
            handleCancel()
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
        } finally {
            isPredictiveBackActive = false
            predictiveBackProgress = 0f
        }
    }

    val editCoverPath = selectedCoverUri?.toString() ?: book?.coverPath ?: book?.thumbnailPath
    val editCoverLastUpdated = book?.coverLastUpdated ?: 0L
    var editCoverColor by remember(editCoverPath, editCoverLastUpdated) {
        mutableStateOf(ImageProcessor.getCachedColor(editCoverPath, editCoverLastUpdated)?.let { Color(it) })
    }
    val darkTheme = LocalDarkTheme.current
    val amoled = LocalAmoled.current
    val editColorScheme = remember(editCoverColor, darkTheme, amoled) {
        editCoverColor?.let { coverColor ->
            dynamicColorScheme(
                seedColor = coverColor,
                isDark = darkTheme,
                isAmoled = amoled,
                style = PaletteStyle.Content
            )
        }
    }

    val contentBlock = @Composable {
    val isBlur = glassEffectMode == GlassEffectMode.Haze && detailHazeState != null

    val editContentColor = MaterialTheme.colorScheme.onSurface

    val density = LocalDensity.current
    val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

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
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .then(
                if (isBlur) {
                    Modifier.hazeEffect(
                        state = detailHazeState,
                        style = HazeMaterials.ultraThin()
                    )
                } else {
                    Modifier
                }
            )
            .background(if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background),
        color = Color.Transparent,
        contentColor = editContentColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            CoverBackground(
                coverPath = editCoverPath,
                lastUpdated = editCoverLastUpdated,
                hazeState = detailHazeState,
                onColorExtracted = { editCoverColor = it }
            )

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
                topBar = {
                    TopAppBar(
                        modifier = Modifier,
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
                                onClick = handleCancel,
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
                            titleContentColor = editContentColor,
                            navigationIconContentColor = editContentColor,
                            actionIconContentColor = editContentColor
                        )
                    )
                },
                containerColor = Color.Transparent,
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
                    var title by remember(book) { mutableStateOf(book.title) }
                    var author by remember(book) { mutableStateOf(book.author) }
                    var narrator by remember(book) { mutableStateOf(book.narrator) }
                    var year by remember(book) { mutableStateOf(book.year) }
                    var series by remember(book) { mutableStateOf(book.series) }
                    var description by remember(book) { mutableStateOf(book.description) }

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

                    val windowClass = LocalAppWindowSizeClass.current
                    val useLandscapeLayout = windowClass.isWideScreen

                    val titleField = @Composable {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(R.string.title_label)) },
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

                    val seriesField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = series,
                            onValueChange = { series = it },
                            label = { Text(stringResource(R.string.series_label)) },
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
                        if (isBlur) {
                            Surface(
                                onClick = {
                                    onSave(
                                        title,
                                        author,
                                        narrator,
                                        year,
                                        description,
                                        series,
                                        selectedCoverUri?.toString()
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .then(
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .hazeEffect(
                                                state = detailHazeState,
                                                style = HazeMaterials.ultraThin()
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
                            Button(
                                onClick = {
                                    onSave(
                                        title,
                                        author,
                                        narrator,
                                        year,
                                        description,
                                        series,
                                        selectedCoverUri?.toString()
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
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        if (useLandscapeLayout) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.Top
                            ) {
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(24.dp)),
                                        sizeRatio = 1.0f
                                    )

                                    changeCoverButton()
                                }

                                Column(
                                    modifier = Modifier.weight(1.8f),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    titleField()
                                    authorField(Modifier.fillMaxWidth())
                                    narratorField(Modifier.fillMaxWidth())
                                    yearField(Modifier.fillMaxWidth())
                                    seriesField(Modifier.fillMaxWidth())
                                    descriptionField()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    saveButton()
                                }
                            }
                        } else {
                            PlayerCover(
                                coverPath = editCoverPath,
                                isPlaying = false,
                                coverLastUpdated = book.coverLastUpdated,
                                coverScene = "edit-main-cover",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp)),
                                sizeRatio = 1.0f
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            changeCoverButton()

                            Spacer(modifier = Modifier.height(8.dp))

                            titleField()
                            authorField(Modifier.fillMaxWidth())
                            narratorField(Modifier.fillMaxWidth())
                            yearField(Modifier.fillMaxWidth())
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

    if (editColorScheme != null) {
        MaterialTheme(colorScheme = animateColorScheme(editColorScheme), content = contentBlock)
    } else {
        contentBlock()
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
    OtoTheme {
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            EditBookScreen(
                book = EditBookDraft(
                    id = "preview-id",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata.",
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
    OtoTheme {
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.LandscapePhone
        ) {
            EditBookScreen(
                book = EditBookDraft(
                    id = "preview-id",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata.",
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
    OtoTheme {
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.LandscapeTablet
        ) {
            EditBookScreen(
                book = EditBookDraft(
                    id = "preview-id",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    year = "2023",
                    description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata.",
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
