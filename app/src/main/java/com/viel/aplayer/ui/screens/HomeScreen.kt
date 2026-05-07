package com.viel.aplayer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.viel.aplayer.R
import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.ui.theme.APlayerTheme
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    audiobooks: List<AudiobookEntity> = emptyList(),
    isMiniPlayerVisible: Boolean = false,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    onImportMedia: (Uri) -> Unit = {},
    onLoadMedia: (Uri, String, String, Long) -> Unit = { _, _, _, _ -> },
) {
    val filters = listOf(
        stringResource(R.string.filter_all),
        stringResource(R.string.filter_not_started),
        stringResource(R.string.filter_in_progress),
        stringResource(R.string.filter_finished)
    )
    var selectedFilter by remember { mutableStateOf(filters[0]) }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Log error or notify user if needed
            }
            onImportMedia(it)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search_content_description)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.settings_content_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    launcher.launch(arrayOf("audio/*"))
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = if (isMiniPlayerVisible) 80.dp else 16.dp)
                    .size(64.dp)
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.import_content_description),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    ) { innerPadding ->
        val recentBooks = audiobooks
            .filter { it.lastPlayedAt > 0 }
            .sortedByDescending { it.lastPlayedAt }
            .take(3)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = if (isMiniPlayerVisible) 100.dp else 16.dp)
        ) {
            // Filters Section
            item {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters) { filter ->
                        FilterChip(
                            selected = filter == selectedFilter,
                            onClick = {
                                selectedFilter = filter
                                // TODO: Implement actual filtering logic for audiobooks list
                            },
                            label = { Text(filter) },
                            leadingIcon = if (filter == selectedFilter) {
                                {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }

            // Recently Played Section
            if (recentBooks.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.recently_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
                    )
                }

                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(recentBooks) { book ->
                            RecentlyItem(
                                title = book.title,
                                author = book.author,
                                progressText = "${((book.lastPosition.toFloat() / book.duration.coerceAtLeast(1L).toFloat()) * 100).toInt()}%",
                                coverPath = book.coverPath,
                                onClick = { onNavigateToDetail(book.uri) }
                            )
                        }
                    }
                }
            }

            // Category Section (e.g., specific author)
            if (audiobooks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Your Collection",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { /* TODO */ }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "See All"
                            )
                        }
                    }
                }

                items(audiobooks) { book ->
                    AudiobookListItem(
                        title = book.title,
                        author = book.author,
                        narrator = book.narrator,
                        duration = book.duration,
                        coverPath = book.coverPath,
                        onClick = { onNavigateToDetail(book.uri) }
                    ) { 
                        onLoadMedia(book.uri.toUri(), book.title, book.author, book.lastPosition)
                        onNavigateToPlayer()
                    }
                }
            }
        }
    }
}

@Composable
fun RecentlyItem(
    title: String,
    author: String,
    progressText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null
) {
    Column(
        modifier = modifier
            .width(160.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if ((coverPath != null) && File(coverPath).exists()) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            // Progress Badge
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = progressText,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AudiobookListItem(
    title: String,
    author: String,
    narrator: String,
    duration: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    onPlayClick: () -> Unit = {}
) {
    ListItem(
        modifier = modifier.clickable { onClick() },
        headlineContent = { 
            Text(
                title, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            ) 
        },
        supportingContent = {
            Column {
                Text("$author • $narrator", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                if ((coverPath != null) && File(coverPath).exists()) {
                    AsyncImage(
                        model = File(coverPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onPlayClick) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play")
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun formatDuration(ms: Long): String {
    val hours = ms / 3600000
    val minutes = (ms % 3600000) / 60000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    APlayerTheme {
        HomeScreen()
    }
}

@Preview(showBackground = true, name = "Home with Data")
@Composable
fun HomeScreenWithDataPreview() {
    val mockBooks = listOf(
        AudiobookEntity(
            uri = "uri1",
            title = "イン・ザ・メガチャーチ",
            author = "朝井 リョウ",
            narrator = "岩崎 了, 大森 ゆき",
            duration = 44580000L,
            lastPosition = 10314400L,
            lastPlayedAt = System.currentTimeMillis(),
        ),
        AudiobookEntity(
            uri = "uri2",
            title = "晓星",
            author = "凑 かなえ",
            narrator = "樱井 孝宏, 早见 沙织",
            duration = 4826000L,
            lastPosition = 3281680L,
            lastPlayedAt = System.currentTimeMillis() - 100000,
        ),
        AudiobookEntity(
            uri = "uri3",
            title = "复仇是合法的",
            author = "三日市 零",
            narrator = "Narrator Name",
            duration = 3600000L,
            lastPosition = 500000L,
            lastPlayedAt = System.currentTimeMillis() - 200000,
        ),
        AudiobookEntity(
            uri = "uri4",
            title = "Sample Book 4",
            author = "Sample Author",
            narrator = "Narrator Name",
            duration = 3600000L,
            lastPosition = 1800000L,
            lastPlayedAt = System.currentTimeMillis() - 300000,
        )
        )
    APlayerTheme {
        HomeScreen(
            audiobooks = mockBooks,
            isMiniPlayerVisible = false
        )
    }
}
