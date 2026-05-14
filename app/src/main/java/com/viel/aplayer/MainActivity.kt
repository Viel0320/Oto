package com.viel.aplayer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.ui.components.CompactMediaPlayer
import com.viel.aplayer.ui.screens.DetailScreen
import com.viel.aplayer.ui.screens.HomeScreen
import com.viel.aplayer.ui.screens.PlayerContentScreen
import com.viel.aplayer.ui.screens.PlayerScreen
import com.viel.aplayer.ui.screens.SearchScreen
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.viewmodel.PlayerViewModel
import com.viel.aplayer.worker.LibrarySyncWorker
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val libraryRepository = LibraryRepository.getInstance(this)
        
        // Trigger library sync on startup
        val syncRequest = OneTimeWorkRequestBuilder<LibrarySyncWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "LibrarySync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )

        setContent {
            APlayerTheme {
                val audiobooks by libraryRepository.audiobooks.collectAsState(initial = emptyList())
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val context = LocalContext.current
                val playerViewModel: PlayerViewModel = viewModel()

                // 测试操作：记录 mini 播放器是否被临时隐藏，界面切换时刷新
                var isMiniPlayerHidden by remember(currentRoute) { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    playerViewModel.initialize(context)
                }
                
                val isPlaying by playerViewModel.isPlaying.collectAsState()
                val currentTitle by playerViewModel.currentTitle.collectAsState()
                val currentAuthor by playerViewModel.currentAuthor.collectAsState()
                val currentNarrator by playerViewModel.currentNarrator.collectAsState()
                val currentCoverPath by playerViewModel.currentCoverPath.collectAsState()
                val currentPositionState = playerViewModel.currentPosition.collectAsState()
                val durationState = playerViewModel.duration.collectAsState()
                val playbackSpeed by playerViewModel.playbackSpeed.collectAsState()
                val selectedSleepTimer by playerViewModel.selectedSleepTimer.collectAsState()
                val currentChapters by playerViewModel.currentChapters.collectAsState()
                val currentSubtitles by playerViewModel.currentSubtitles.collectAsState()
                val currentBookmarks by playerViewModel.currentBookmarks.collectAsState()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val hasActiveTrack = currentTitle.isNotEmpty() && currentTitle != "Unknown Title"

                        val showMiniPlayer = currentRoute != "player" && 
                                             currentRoute != "content/{tab}" && 
                                             currentRoute != "search" &&
                                             hasActiveTrack

                        NavHost(
                            navController = navController,
                            startDestination = "home",
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable("home") {
                                HomeScreen(
                                    audiobooks = audiobooks,
                                    isMiniPlayerVisible = hasActiveTrack,
                                    onNavigateToDetail = { uri: String ->
                                        navController.navigate("detail?bookUri=${Uri.encode(uri)}")
                                    },
                                    onNavigateToSearch = {
                                        navController.navigate("search")
                                    },
                                    onLoadMedia = { uri: Uri, title: String, author: String, narrator: String, pos: Long -> 
                                        playerViewModel.loadMedia(uri, title, author, narrator, pos) 
                                    },
                                    onNavigateToPlayer = { navController.navigate("player") }
                                )
                            }
                            composable(
                                "search",
                                enterTransition = { fadeIn(animationSpec = tween(400)) },
                                exitTransition = { fadeOut(animationSpec = tween(400)) },
                                popEnterTransition = { fadeIn(animationSpec = tween(400)) },
                                popExitTransition = { fadeOut(animationSpec = tween(400)) }
                            ) {
                                SearchScreen(
                                    onBack = { navController.popBackStack() },
                                    onNavigateToDetail = { uri: String ->
                                        navController.navigate("detail?bookUri=${Uri.encode(uri)}")
                                    }
                                )
                            }
                            composable(
                                "detail?bookUri={bookUri}",
                                enterTransition = {
                                    slideInVertically(initialOffsetY = { it }, animationSpec = tween(400))
                                },
                                exitTransition = {
                                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                                },
                                popEnterTransition = {
                                    fadeIn(animationSpec = tween(400))
                                },
                                popExitTransition = {
                                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                                }
                            ) { backStackEntry ->
                                val bookUri = backStackEntry.arguments?.getString("bookUri")
                                val selectedBook = audiobooks.find { it.uri == bookUri }
                                
                                val isAvailable = remember(selectedBook) {
                                    selectedBook?.let { libraryRepository.checkFileExists(it.uri) } ?: false
                                }

                                val progressPercent = if (selectedBook != null && selectedBook.duration > 0) {
                                    ((selectedBook.lastPosition.toFloat() / selectedBook.duration.toFloat()) * 100).toInt()
                                } else 0

                                DetailScreen(
                                    title = selectedBook?.title ?: "Unknown Title",
                                    author = selectedBook?.author ?: "Unknown Author",
                                    narrator = selectedBook?.narrator ?: "Unknown Narrator",
                                    description = selectedBook?.description ?: "",
                                    coverPath = selectedBook?.coverPath,
                                    duration = formatDuration(selectedBook?.duration ?: 0L),
                                    year = if (!selectedBook?.year.isNullOrBlank()) selectedBook.year else "Unknown",
                                    fileSize = formatFileSize(selectedBook?.fileSize ?: 0L),
                                    progressPercent = progressPercent,
                                    isAvailable = isAvailable,
                                    onBackClick = { navController.popBackStack() },
                                    onPlayClick = { 
                                        selectedBook?.let { book ->
                                            playerViewModel.loadMedia(
                                                uri = book.uri.toUri(), 
                                                title = book.title, 
                                                author = book.author,
                                                narrator = book.narrator,
                                                startPositionMs = book.lastPosition
                                            )
                                        }
                                        navController.navigate("player") 
                                    }
                                )
                            }

                            composable(
                                "player",
                                enterTransition = {
                                    slideInVertically(initialOffsetY = { it }, animationSpec = tween(400))
                                },
                                exitTransition = {
                                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                                },
                                popEnterTransition = {
                                    fadeIn(animationSpec = tween(400))
                                },
                                popExitTransition = {
                                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                                }
                            ) {
                                val currentPositionProvider = remember(currentPositionState) { { currentPositionState.value } }
                                val durationProvider = remember(durationState) { { durationState.value } }

                                PlayerScreen(
                                    onMinimize = remember { { navController.popBackStack() } },
                                    isPlaying = isPlaying,
                                    title = currentTitle,
                                    author = currentAuthor,
                                    narrator = currentNarrator,
                                    coverPath = currentCoverPath,
                                    currentPosition = currentPositionProvider,
                                    duration = durationProvider,
                                    chapters = currentChapters,
                                    onSeek = remember { { pos: Long -> playerViewModel.seekTo(pos) } },
                                    onSkipForward = remember { { playerViewModel.skipForward() } },
                                    onSkipBackward = remember { { playerViewModel.skipBackward() } },
                                    onPlayPauseClick = remember { { playerViewModel.togglePlayPause() } },
                                    playbackSpeed = playbackSpeed,
                                    onSpeedChange = remember { { speed: Float -> playerViewModel.setPlaybackSpeed(speed) } },
                                    selectedSleepTimer = selectedSleepTimer,
                                    onSleepTimerChange = remember { { mins: Int -> playerViewModel.setSleepTimer(mins) } },
                                    onSubtitlesClick = remember { { navController.navigate("content/1") } },
                                    onBookmarksClick = remember { { navController.navigate("content/0") } },
                                    onRelatedClick = remember { { navController.navigate("content/2") } },
                                    onAddBookmark = { title -> playerViewModel.addBookmark(title) }
                                )
                            }

                            composable(
                                "content/{tab}",
                                enterTransition = {
                                    slideInVertically(initialOffsetY = { it }, animationSpec = tween(400))
                                },
                                exitTransition = {
                                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                                },
                                popEnterTransition = {
                                    fadeIn(animationSpec = tween(400))
                                },
                                popExitTransition = {
                                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                                }
                            ) { backStackEntry ->
                                val tab = backStackEntry.arguments?.getString("tab")?.toIntOrNull() ?: 1
                                PlayerContentScreen(
                                    title = currentTitle,
                                    author = currentAuthor,
                                    narrator = currentNarrator,
                                    currentPosition = currentPositionState.value,
                                    duration = durationState.value,
                                    isPlaying = isPlaying,
                                    playbackSpeed = playbackSpeed,
                                    selectedSleepTimer = selectedSleepTimer,
                                    subtitles = currentSubtitles,
                                    bookmarks = currentBookmarks,
                                    chapters = currentChapters,
                                    onSeek = { pos, allowUndo -> playerViewModel.seekTo(pos, allowUndo) },
                                    onDeleteBookmark = { bookmark -> playerViewModel.deleteBookmark(bookmark) },
                                    onAddBookmark = { title -> playerViewModel.addBookmark(title) },
                                    onPlayPauseClick = { playerViewModel.togglePlayPause() },
                                    onSkipForward = { playerViewModel.skipForward() },
                                    onSkipBackward = { playerViewModel.skipBackward() },
                                    onSpeedChange = { speed -> playerViewModel.setPlaybackSpeed(speed) },
                                    onSleepTimerChange = { mins -> playerViewModel.setSleepTimer(mins) },
                                    onClose = { navController.popBackStack() },
                                    initialTab = tab,
                                    coverPath = currentCoverPath,
                                    showUndoSeek = playerViewModel.showUndoSeek.collectAsState().value,
                                    onUndoSeek = { playerViewModel.undoSeek() }
                                )
                            }
                        }

                        // 迷你播放器
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showMiniPlayer && !isMiniPlayerHidden,
                            enter = fadeIn(animationSpec = tween(400)),
                            exit = fadeOut(animationSpec = tween(400)) +
                                   slideOutVertically(
                                       targetOffsetY = { it },
                                       animationSpec = tween(400)
                                   ),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                        ) {
                            Box(modifier = Modifier.clickable { navController.navigate("player") }) {
                                CompactMediaPlayer(
                                    isPlaying = isPlaying,
                                    title = currentTitle,
                                    author = currentAuthor,
                                    narrator = currentNarrator,
                                    coverPath = currentCoverPath,
                                    progress = {
                                        val d = durationState.value
                                        if (d > 0) currentPositionState.value.toFloat() / d.toFloat() else 0f
                                    },
                                    onPlayPauseClick = { playerViewModel.togglePlayPause() },
                                    onLongClickCover = { isMiniPlayerHidden = true } // 测试操作：长按封面隐藏
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatFileSize(sizeInBytes: Long): String {
        if (sizeInBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(sizeInBytes.toDouble()) / log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", sizeInBytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}
