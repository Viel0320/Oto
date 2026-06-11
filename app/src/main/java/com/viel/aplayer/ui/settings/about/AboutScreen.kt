package com.viel.aplayer.ui.settings.about

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.viel.aplayer.BuildConfig
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Open-source library entity (Model representing open source dependency info)
 * Holds descriptive metadata and licensing strings for a single library.
 */
data class OpenSourceLibrary(
    val name: String,
    val developer: String,
    val license: String,
    @StringRes val descriptionRes: Int,
    val url: String,
    val licenseText: String
)

/**
 * Open-source libraries static list (Database of dependency details)
 * Organizes APlayer core dependencies to help users explore licenses.
 */
private val openSourceLibraries = listOf(
    OpenSourceLibrary(
        name = "Jetpack Compose",
        developer = "Google / AndroidX Project",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_compose_description,
        url = "https://developer.android.com/compose",
        licenseText = "Copyright The Android Open Source Project\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        name = "AndroidX Media3 (ExoPlayer)",
        developer = "Google / AndroidX Project",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_media3_description,
        url = "https://github.com/androidx/media",
        licenseText = "Copyright The Android Open Source Project\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        name = "AndroidX Room",
        developer = "Google / AndroidX Project",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_room_description,
        url = "https://developer.android.com/training/data-storage/room",
        licenseText = "Copyright The Android Open Source Project\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        // Open Source Haze (Update Open Source License Library) Replace old blur license details with Chris Banes' Haze.
        name = "Haze",
        developer = "Chris Banes",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_haze_description,
        url = "https://github.com/chrisbanes/haze",
        licenseText = "Copyright 2023 Chris Banes\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        name = "Coil Compose",
        developer = "Coil Contributors",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_coil_description,
        url = "https://github.com/coil-kt/coil",
        licenseText = "Copyright 2023 Coil Contributors\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        name = "Jetpack Glance",
        developer = "Google / AndroidX Project",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_glance_description,
        url = "https://developer.android.com/jetpack/androidx/releases/glance",
        licenseText = "Copyright The Android Open Source Project\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        name = "Kotlin Coroutines",
        developer = "JetBrains",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_coroutines_description,
        url = "https://github.com/Kotlin/kotlinx.coroutines",
        licenseText = "Copyright 2000-2023 JetBrains s.r.o. and contributors\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        name = "AndroidX DataStore Preferences",
        developer = "Google / AndroidX Project",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_datastore_description,
        url = "https://developer.android.com/topic/libraries/architecture/datastore",
        licenseText = "Copyright The Android Open Source Project\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    ),
    OpenSourceLibrary(
        name = "AndroidX WorkManager",
        developer = "Google / AndroidX Project",
        license = "Apache License 2.0",
        descriptionRes = R.string.about_library_workmanager_description,
        url = "https://developer.android.com/topic/libraries/architecture/workmanager",
        licenseText = "Copyright The Android Open Source Project\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0"
    )
)

/**
 * Open-source licenses view (Component displaying list of library licenses)
 * Renders brand header card followed by lists of dependency details in landscape and portrait views.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutLibrariesScreen(
    onBack: () -> Unit,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    aboutHazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    // Resolve window proportions (To adapt widths dynamically across diverse display configurations)
    // Employs unified WindowClass instead of reading LocalConfiguration directly.
    val windowClass = LocalWindowClass.current
    val isLandscape = windowClass.isLandscape
    val isWideScreen = windowClass.isTablet
    val useWideLayout = windowClass.isWideScreen

    // Read window bounds (To calculate padding boundaries under active displays)
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val startPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    var aboutTopBarHeightPx by remember { mutableIntStateOf(0) }
    val resolvedAboutHazeState = aboutHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    // About Top Bar Height Resolution (Reserve space for shared overlay chrome)
    // The measured top bar height preserves the previous Scaffold topBar spacing while allowing license content to scroll beneath the glass layer.
    val measuredAboutTopBarHeight = if (aboutTopBarHeightPx > 0) {
        with(density) { aboutTopBarHeightPx.toDp() }
    } else {
        safeDrawingPadding.calculateTopPadding() + 64.dp
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (useWideLayout) 0.8f else 1f)
        ) {
            Scaffold(
                modifier = Modifier
                    // About Content Surface Bounds (Keep the sampled license panel full size)
                    // The shared glass top bar needs a complete backdrop across the centered about panel, including the blank background above the first list row.
                    .fillMaxSize()
                    .then(
                        if (resolvedAboutHazeState != null) {
                            // About Content Haze Source (Expose license content to overlay chrome)
                            // Registering the Scaffold content layer lets the shared top bar blur the About page without sampling its own toolbar.
                            Modifier.hazeSource(resolvedAboutHazeState)
                        } else {
                            Modifier
                        }
                    ),
                // Title: Avoid About Scaffold Background Overdraw (Set Scaffold containerColor to transparent)
                // Overrides Scaffold background containerColor to transparent so it won't draw another background layer over SettingsOverlay.
                containerColor = Color.Transparent,
                // About Content Insets (Let overlay top bar own top spacing)
                // The list keeps bottom safe-area padding while the measured shared top bar supplies the top content offset.
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = startPadding + 16.dp,
                        end = endPadding + 16.dp,
                        top = measuredAboutTopBarHeight + 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Renders branding layout (To display logo and greetings card)
                    item {
                        BrandHeaderCard()
                    }

                    // Renders license library list (To display each library description card)
                    items(openSourceLibraries, key = { it.name }) { library ->
                        LibraryCard(
                            library = library,
                            onVisitUrl = { url ->
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    // Handle web launch errors (To catch intent dispatch failures safely)
                                }
                            }
                        )
                    }
                }
            }
            APlayerGlassTopBar(
                glassEffectMode = glassEffectMode,
                hazeState = resolvedAboutHazeState,
                onHeightChanged = { aboutTopBarHeightPx = it },
                // About Top Bar Overlay Placement (Reuse Home's extracted glass chrome)
                // Drawing the license header above Scaffold content keeps About visually aligned with Settings while preserving independent screen content ownership.
                modifier = Modifier.align(Alignment.TopCenter),
                title = {
                    Text(
                        text = stringResource(R.string.settings_open_source_license_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description)
                        )
                    }
                }
            )
        }
    }
}

/**
 * Branding detail card (Component rendering APlayer identity logo and greetings text)
 * Builds styled backdrop gradient container with rounded edges.
 */
@Composable
private fun BrandHeaderCard() {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    // Runtime Version Label (Read Gradle-owned app version metadata instead of storing a release value in translations)
    // The localized string resource owns only the "Version %s" template, while build.gradle.kts owns the actual version name.
    val versionName = remember { BuildConfig.VERSION_NAME }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Styled logo image (To draw vector info icon over gradient backdrop)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(primaryColor, secondaryColor)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Localized App Brand Copy (Read the displayed app name from resources instead of repeating a literal)
            // The current translations keep the brand stable, but resource lookup keeps the about page aligned with manifest-visible app naming.
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.about_version_text, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.about_app_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Open-source library layout card (Component rendering expandable license info item)
 * Supports dynamic resize transitions (animateContentSize) to fold or unfold license text.
 */
@Composable
private fun LibraryCard(
    library: OpenSourceLibrary,
    onVisitUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val libraryDescription = stringResource(library.descriptionRes)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(), // Transition resize animator (To provide smooth accordion animation effects)
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (expanded) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // License label container (To group license type information tags)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = library.license,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 10.sp
                            )
                        }
                        
                        Text(
                            text = stringResource(R.string.about_developer_credit, library.developer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Anchor web hyperlink (To navigate users to project host site)
                IconButton(
                    onClick = { onVisitUrl(library.url) },
                    // Project Link Target (Keep the icon-only link at the 48.dp accessibility floor)
                    // The visible OpenInNew glyph stays small, while the button surface remains reliable for touch, TalkBack, and Switch Access.
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.about_visit_project_homepage_content_description),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Toggle brief description (To truncate texts under folded view states)
            Text(
                text = libraryDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            // Toggle accordion section (To reveal license text details with expand transition animation)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    // Section line separator (To divide metadata summary from detailed license texts)
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.about_license_details_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // License block backdrop (To render monospace texts on shaded gray backgrounds)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = library.licenseText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { onVisitUrl(library.url) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.about_visit_project_homepage), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Convenient BorderStroke factory (To create border properties dynamically)
 */
@Composable
private fun borderStroke(width: Dp, color: Color) =
    BorderStroke(width, color)

/**
 * Licenses view preview (To preview component styling)
 */
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun AboutLibrariesScreenPreview() {
    APlayerTheme {
        // Portrait phone preview (To preview licenses layout in portrait constraints)
        // Uses CompositionLocalProvider to inject vertical phone window attributes.
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            AboutLibrariesScreen(onBack = {})
        }
    }
}
