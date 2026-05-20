package com.viel.aplayer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.data.BookWithProgress
import com.viel.aplayer.data.BookEntity
import com.viel.aplayer.ui.components.APlayerFilterChip
import com.viel.aplayer.ui.components.AudiobookListItem
import com.viel.aplayer.ui.components.RecentlyItem
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.launch

/**
 * 首页图书馆的过滤选项枚举。
 */
enum class HomeFilter {
    /** 正在阅读（播放进度 > 0 且未读完） */
    InProgress,
    /** 未开始 */
    NotStarted,
    /** 已读完 */
    Finished
}

private fun BookWithProgress.matchesFilter(filter: HomeFilter): Boolean {
    return when (filter) {
        HomeFilter.NotStarted -> isNotStarted
        HomeFilter.InProgress -> isInProgress
        HomeFilter.Finished -> isFinished
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    audiobooks: List<BookWithProgress> = emptyList(),
    selectedFilter: HomeFilter = HomeFilter.NotStarted,
    onFilterSelected: (HomeFilter) -> Unit = {},
    isMiniPlayerVisible: Boolean = false,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    onLoadBook: (String) -> Unit = {},
    onLibraryRootSelected: (Uri) -> Unit = {},
) {
    val filters = listOf(
        HomeFilter.NotStarted to stringResource(R.string.filter_not_started),
        HomeFilter.InProgress to stringResource(R.string.filter_in_progress),
        HomeFilter.Finished to stringResource(R.string.filter_finished)
    )
    val filteredAudiobooks = remember(audiobooks, selectedFilter) {
        audiobooks.filter { it.matchesFilter(selectedFilter) }
    }
    val groupedByAuthor = remember(filteredAudiobooks) {
        filteredAudiobooks.groupBy { it.book.author }
    }
    val recentBooks = remember(audiobooks, selectedFilter) {
        when (selectedFilter) {
            HomeFilter.NotStarted -> audiobooks.filter { it.isNotStarted }
                .sortedByDescending { it.book.addedAt }
                .take(10)
            HomeFilter.InProgress -> audiobooks.filter { it.isInProgress && (it.progress?.lastPlayedAt ?: 0) > 0 }
                .sortedByDescending { it.progress?.lastPlayedAt ?: 0 }
                .take(5)
            else -> emptyList()
        }
    }
    val recentTitle = when (selectedFilter) {
        HomeFilter.NotStarted -> stringResource(R.string.recently_added_title)
        HomeFilter.InProgress -> stringResource(R.string.recently_played_title)
        else -> ""
    }
    val shouldShowRecentBooks = (selectedFilter == HomeFilter.NotStarted || selectedFilter == HomeFilter.InProgress) && recentBooks.isNotEmpty()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
                        ),
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scope.launch {
                                        listState.scrollToItem(0)
                                    }
                                }
                            )
                        }
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
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
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.import_content_description),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { (filter, label) ->
                    APlayerFilterChip(
                        selected = filter == selectedFilter,
                        onClick = { onFilterSelected(filter) },
                        label = label
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = innerPadding.calculateBottomPadding() + (if (isMiniPlayerVisible) 80.dp else 0.dp) + 16.dp
                )
            ) {
                if (shouldShowRecentBooks) {
                    item {
                        Text(
                            text = recentTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }

                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(recentBooks) { book ->
                                RecentlyItem(
                                    title = book.book.title,
                                    author = book.book.author,
                                    narrator = book.book.narrator,
                                    progressText = if (book.progressPercent > 0) "${book.progressPercent}%" else "NEW",
                                    coverPath = book.book.thumbnailPath ?: book.book.coverPath,
                                    coverLastUpdated = book.book.lastScannedAt, // 详尽中文注释：桥接 Room 中的扫描/更新时间戳，令 Coil 声明式打破缓存以即时更新界面
                                    onClick = { onNavigateToDetail(book.book.id) }
                                )
                            }
                        }
                    }
                }

                groupedByAuthor.forEach { (author, books) ->
                    item {
                        Text(
                            text = author.takeIf { it.isNotBlank() } ?: "Unknown",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                        )
                    }

                    items(books) { book ->
                        AudiobookListItem(
                            title = book.book.title,
                            author = book.book.author,
                            narrator = book.book.narrator,
                            duration = book.book.totalDurationMs,
                            coverPath = book.book.thumbnailPath ?: book.book.coverPath,
                            coverLastUpdated = book.book.lastScannedAt, // 详尽中文注释：桥接 Room 层中的扫描/自愈重建毫秒时间戳，使用声明式设计促成图片同步强绘刷新
                            progressPercent = book.progressPercent,
                            onClick = { onNavigateToDetail(book.book.id) }
                        ) { 
                            onLoadBook(book.book.id)
                            onNavigateToPlayer()
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun HomeScreenNotStartedPreview() {
    val mockBooks = listOf(
        BookWithProgress(
            book = BookEntity(
                id = "id1",
                // Preview data follows the new logical-book model.
                rootId = "preview-root",
                sourceType = "SINGLE_AUDIO",
                title = "In the Megachurch",
                author = "Ryo Asai",
                narrator = "Narrator A",
                totalDurationMs = 44580000L,
                addedAt = System.currentTimeMillis()
            ),
            progress = null
        )
    )

    APlayerTheme {
        HomeScreen(
            audiobooks = mockBooks,
            selectedFilter = HomeFilter.NotStarted,
            isMiniPlayerVisible = false
        )
    }
}
