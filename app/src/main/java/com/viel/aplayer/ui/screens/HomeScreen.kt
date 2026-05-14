package com.viel.aplayer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import com.viel.aplayer.ui.utils.formatCompactDuration
import com.viel.aplayer.ui.utils.formatPeopleSubtitle
import java.io.File

enum class HomeFilter {
    All,
    NotStarted,
    InProgress,
    Finished
}

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
    onSeeAllClick: () -> Unit = {},
    onLoadMedia: (Uri, String, String, String, Long) -> Unit = { _, _, _, _, _ -> },
    onLibraryRootSelected: (Uri) -> Unit = {},
) {
    val filters = listOf(
        HomeFilter.All to stringResource(R.string.filter_all),
        HomeFilter.NotStarted to stringResource(R.string.filter_not_started),
        HomeFilter.InProgress to stringResource(R.string.filter_in_progress),
        HomeFilter.Finished to stringResource(R.string.filter_finished)
    )
    var selectedFilter by remember { mutableStateOf(HomeFilter.All) }
    val filteredAudiobooks = remember(audiobooks, selectedFilter) {
        audiobooks.filter { it.matchesFilter(selectedFilter) }
    }
    val recentBooks = remember(audiobooks) {
        audiobooks
            .filter { it.lastPlayedAt > 0 }
            .sortedByDescending { it.lastPlayedAt }
            .take(3)
    }
    val shouldShowRecentBooks = selectedFilter == HomeFilter.All || selectedFilter == HomeFilter.InProgress
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    launcher.launch(null)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = if (isMiniPlayerVisible) 80.dp else 16.dp)
                    .navigationBarsPadding()
                    .size(64.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_rounded_add),
                    contentDescription = stringResource(R.string.import_content_description),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + (if (isMiniPlayerVisible) 80.dp else 0.dp) + 16.dp
            )
        ) {
            item {
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
                                painterResource(R.drawable.ic_rounded_search),
                                contentDescription = stringResource(R.string.search_content_description)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                painterResource(R.drawable.ic_rounded_tune),
                                contentDescription = stringResource(R.string.settings_content_description)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
            // Filters Section
            item {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters) { (filter, label) ->
                        FilterChip(
                            selected = filter == selectedFilter,
                            onClick = { selectedFilter = filter },
                            label = { Text(label) },
                            leadingIcon = if (filter == selectedFilter) {
                                {
                                    Icon(
                                        painterResource(R.drawable.ic_rounded_check),
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
            if (shouldShowRecentBooks && recentBooks.isNotEmpty()) {
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
                                progressText = "${kotlin.math.ceil(book.lastPosition.toDouble() / book.duration.coerceAtLeast(1L).toDouble() * 100).toInt()}%",
                                coverPath = book.thumbnailPath ?: book.coverPath,
                                onClick = { onNavigateToDetail(book.uri) }
                            )
                        }
                    }
                }
            }

            // Category Section (e.g., specific author)
            if (filteredAudiobooks.isNotEmpty()) {
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
                        IconButton(onClick = onSeeAllClick) {
                            Icon(
                                painterResource(R.drawable.ic_rounded_arrow_forward),
                                contentDescription = "See All"
                            )
                        }
                    }
                }

                items(filteredAudiobooks) { book ->
                    AudiobookListItem(
                        title = book.title,
                        author = book.author,
                        narrator = book.narrator,
                        duration = book.duration,
                        coverPath = book.thumbnailPath ?: book.coverPath,
                        onClick = { onNavigateToDetail(book.uri) }
                    ) { 
                        onLoadMedia(book.uri.toUri(), book.title, book.author, book.narrator, book.lastPosition)
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
                        painterResource(R.drawable.ic_rounded_play_arrow),
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
                Text(formatPeopleSubtitle(author, narrator), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = formatCompactDuration(duration),
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
                        Icon(painterResource(R.drawable.ic_rounded_play_arrow), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onPlayClick) {
                Icon(painterResource(R.drawable.ic_rounded_play_arrow), contentDescription = "Play")
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun HomeScreenPreview() {
    val mockBooks = listOf(
        AudiobookEntity(
            uri = "uri1",
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            duration = 44580000L,
            lastPosition = 10314400L,
            lastPlayedAt = System.currentTimeMillis(),
        ),
        AudiobookEntity(
            uri = "uri2",
            title = "Dawn Star",
            author = "Kanae Minato",
            narrator = "Narrator B",
            duration = 4826000L,
            lastPosition = 3281680L,
            lastPlayedAt = System.currentTimeMillis() - 100000,
        ),
        AudiobookEntity(
            uri = "uri3",
            title = "Legal Revenge",
            author = "Rei Mikkaichi",
            narrator = "Narrator C",
            duration = 3600000L,
            lastPosition = 500000L,
            lastPlayedAt = System.currentTimeMillis() - 200000,
        ),
        AudiobookEntity(
            uri = "uri4",
            title = "Sample Book 4",
            author = "Sample Author",
            narrator = "Narrator D",
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

private fun AudiobookEntity.matchesFilter(filter: HomeFilter): Boolean {
    return when (filter) {
        HomeFilter.All -> true
        HomeFilter.NotStarted -> lastPosition <= 0L
        HomeFilter.InProgress -> {
            val finishThreshold = (duration * 0.98f).toLong()
            lastPosition > 0L && (duration <= 0L || lastPosition < finishThreshold)
        }
        HomeFilter.Finished -> duration > 0L && lastPosition >= (duration * 0.98f).toLong()
    }
}
