package com.viel.oto.ui.settings.about

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.viel.oto.BuildConfig
import com.viel.oto.R
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.OtoGlassTopBar
import com.viel.oto.ui.common.layout.AppWindowSizeClass
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.settings.components.SectionsColumns
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Generated open-source licenses view.
 * Loads Gradle-generated AboutLibraries metadata and renders it through the app's responsive independent columns.
 */
@Composable
fun AboutLibrariesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    aboutHazeState: HazeState? = null
) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val uriHandler = LocalUriHandler.current
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val windowClass = LocalAppWindowSizeClass.current
    val screenHorizontalPadding = windowClass.screenHorizontalPadding
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val startPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    var aboutTopBarHeightPx by remember { mutableIntStateOf(0) }
    val resolvedAboutHazeState = aboutHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    val openProjectUrl: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    val measuredAboutTopBarHeight = if (aboutTopBarHeightPx > 0) {
        with(density) { aboutTopBarHeightPx.toDp() }
    } else {
        safeDrawingPadding.calculateTopPadding() + 64.dp
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (resolvedAboutHazeState != null) {
                        Modifier.hazeSource(resolvedAboutHazeState)
                    } else {
                        Modifier
                    }
                ),
            containerColor = if (resolvedAboutHazeState != null) MaterialTheme.colorScheme.background else Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            val licenseContentPadding = PaddingValues(
                start = startPadding + screenHorizontalPadding,
                end = endPadding + screenHorizontalPadding,
                top = measuredAboutTopBarHeight + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            )

            if (libraries == null) {
                LoadingLicensesContent(contentPadding = licenseContentPadding)
            } else {
                GeneratedLibrariesColumns(
                    libraries = libraries?.libraries.orEmpty(),
                    columnsCount = windowClass.columnsCount,
                    contentPadding = licenseContentPadding,
                    onVisitUrl = openProjectUrl
                )
            }
        }

        OtoGlassTopBar(
            glassEffectMode = glassEffectMode,
            hazeState = resolvedAboutHazeState,
            onHeightChanged = { aboutTopBarHeightPx = it },
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

/**
 * Responsive independent columns for generated AboutLibraries rows.
 * Keeps each column's vertical spacing isolated so expanding a card does not push gaps into adjacent columns.
 */
@Composable
private fun GeneratedLibrariesColumns(
    libraries: List<Library>,
    columnsCount: Int,
    contentPadding: PaddingValues,
    onVisitUrl: (String) -> Unit
) {
    val expandedLibraryIds = remember(libraries) { mutableStateMapOf<String, Boolean>() }

    SectionsColumns(
        columnsCount = columnsCount,
        itemCount = libraries.size,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        header = {
            BrandHeaderCard(modifier = Modifier.fillMaxWidth())
        }
    ) { libraryIndex ->
        val library = libraries[libraryIndex]
        val libraryKey = library.stableAboutKey()
        val expanded = expandedLibraryIds[libraryKey] == true
        GeneratedLibraryCard(
            library = library,
            expanded = expanded,
            onToggle = { expandedLibraryIds[libraryKey] = !expanded },
            onVisitUrl = onVisitUrl
        )
    }
}
/**
 * Loading placeholder for generated license metadata.
 * Keeps the first-frame layout aligned with the final content while AboutLibraries parses the generated JSON off the main thread.
 */
@Composable
private fun LoadingLicensesContent(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Branding detail card shown before generated license rows.
 * Displays app identity and Gradle-owned version metadata without mixing it into generated third-party notices.
 */
@Composable
private fun BrandHeaderCard(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val versionName = remember { BuildConfig.VERSION_NAME }

    Card(
        modifier = modifier,
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
 * Generated library card styled as a compact notice sheet.
 * The collapsed layout mirrors system license rows by keeping title/version, description, and license in separated bands,
 * while the expanded section remains app-owned so generated license text and project links stay discoverable.
 */
@Composable
private fun GeneratedLibraryCard(
    library: Library,
    expanded: Boolean,
    onToggle: () -> Unit,
    onVisitUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val licenseLabel = library.primaryLicenseLabel()
    val developerName = library.primaryDeveloperName()
    val description = library.descriptionText()
    val licenseText = library.licenseDetailsText()
    val versionLabel = library.versionLabel()
    val projectUrl = library.projectUrl()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (expanded) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.46f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (versionLabel != null) {
                        Text(
                            text = versionLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                            modifier = Modifier.padding(top = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = developerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))

            Text(
                text = licenseLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.about_license_details_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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
                            text = licenseText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }

                    if (projectUrl != null) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Keep the external-link action discoverable by its localized purpose,
                        // because the visible label also carries extra source hints.
                        val visitProjectDescription =
                            stringResource(R.string.about_visit_project_homepage_content_description)

                        OutlinedButton(
                            onClick = { onVisitUrl(projectUrl) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = visitProjectDescription },
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
                            Text(
                                text = stringResource(R.string.about_visit_project_homepage),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds a stable key for generated dependency cards.
 * Keeps expansion state tied to the generated coordinate even when responsive columns are redistributed.
 */
private fun Library.stableAboutKey(): String = artifactId.takeIf { it.isNotBlank() }
    ?: uniqueId.takeIf { it.isNotBlank() }
    ?: name

/**
 * Resolves the compact license badge text shown on generated cards.
 * Prefers SPDX identifiers when available because they are short enough for the old pill-style badge.
 */
@Composable
private fun Library.primaryLicenseLabel(): String = licenses.firstOrNull()
    ?.let { license -> license.spdxId?.takeIf { it.isNotBlank() } ?: license.name.takeIf { it.isNotBlank() } }
    ?: stringResource(R.string.common_unknown)

/**
 * Resolves the developer credit line shown under the generated library name.
 * Falls back through developer, organization, and Gradle coordinates so every generated dependency keeps a stable subtitle.
 */
private fun Library.primaryDeveloperName(): String = developers.firstNotNullOfOrNull { developer ->
    developer.name?.takeIf { it.isNotBlank() }
} ?: organization?.name?.takeIf { it.isNotBlank() }
    ?: uniqueId.substringBefore(':').takeIf { it.isNotBlank() }
    ?: name

/**
 * Resolves the version text shown at the end of the library title row.
 * Generated metadata may omit artifact versions for locally merged entries, so blank values are hidden instead of reserving space.
 */
private fun Library.versionLabel(): String? = artifactVersion?.takeIf { it.isNotBlank() }

/**
 * Resolves the short body text shown before expansion.
 * Generated POM descriptions are used when present; otherwise the Gradle coordinate keeps the card informative without adding manual copy.
 */
private fun Library.descriptionText(): String = description?.takeIf { it.isNotBlank() }
    ?: artifactId.takeIf { it.isNotBlank() }
    ?: uniqueId

/**
 * Resolves the inline license text shown in the expanded card.
 * Uses generated license content first, then falls back to license metadata and finally the library coordinate.
 */
private fun Library.licenseDetailsText(): String = licenses.firstNotNullOfOrNull { license ->
    license.licenseContent?.takeIf { it.isNotBlank() }
} ?: licenses.joinToString(separator = "\n\n") { license ->
    listOfNotNull(
        license.spdxId?.takeIf { it.isNotBlank() },
        license.name.takeIf { it.isNotBlank() },
        license.url?.takeIf { it.isNotBlank() }
    ).joinToString(separator = "\n")
}.takeIf { it.isNotBlank() }
    ?: artifactId

/**
 * Resolves the best generated homepage target for the external-link actions.
 * Website and SCM URLs point users at the project first; license URLs remain a fallback when project metadata is absent.
 */
private fun Library.projectUrl(): String? = website?.takeIf { it.isNotBlank() }
    ?: scm?.url?.takeIf { it.isNotBlank() }
    ?: licenses.firstNotNullOfOrNull { it.url?.takeIf { url -> url.isNotBlank() } }

/**
 * Licenses view preview.
 * Injects the portrait window class used by Settings so preview layout matches the runtime shell constraints.
 */
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun AboutLibrariesScreenPreview() {
    OtoTheme {
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            AboutLibrariesScreen(onBack = {})
        }
    }
}
