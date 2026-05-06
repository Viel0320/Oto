package com.viel.aplayer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.ui.components.CompactMediaPlayer
import com.viel.aplayer.ui.screens.DetailScreen
import com.viel.aplayer.ui.screens.HomeScreen
import com.viel.aplayer.ui.screens.PlayerScreen
import com.viel.aplayer.ui.screens.SearchScreen
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.viewmodel.PlayerViewModel
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val libraryRepository = LibraryRepository.getInstance(this)

        setContent {
            APlayerTheme {
                val audiobooks by libraryRepository.audiobooks.collectAsState(initial = emptyList())
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val context = LocalContext.current
                val playerViewModel: PlayerViewModel = viewModel()

                LaunchedEffect(Unit) {
                    playerViewModel.initialize(context)
                }
                
                val isPlaying by playerViewModel.isPlaying.collectAsState()
                val currentTitle by playerViewModel.currentTitle.collectAsState()
                val currentAuthor by playerViewModel.currentAuthor.collectAsState()
                val currentCoverPath by playerViewModel.currentCoverPath.collectAsState()
                val currentPositionState = playerViewModel.currentPosition.collectAsState()
                val durationState = playerViewModel.duration.collectAsState()
                val playbackSpeed by playerViewModel.playbackSpeed.collectAsState()
                val selectedSleepTimer by playerViewModel.selectedSleepTimer.collectAsState()
                val currentChapters by playerViewModel.currentChapters.collectAsState()
                val playbackState by playerViewModel.playbackState.collectAsState()

                SharedTransitionLayout {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val hasActiveTrack = currentTitle.isNotEmpty() &&
                                                 ((playbackState == androidx.media3.common.Player.STATE_READY) ||
                                                  (playbackState == androidx.media3.common.Player.STATE_BUFFERING))

                            val showMiniPlayer = currentRoute != "player" && hasActiveTrack

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
                                        onImportMedia = { uri: Uri -> libraryRepository.addAudiobook(uri) },
                                        onLoadMedia = { uri: Uri, title: String, author: String, pos: Long -> 
                                            playerViewModel.loadMedia(uri, title, author, pos) 
                                        },
                                        onNavigateToPlayer = { navController.navigate("player") }
                                    )
                                }
                                composable(
                                    "search",
                                    enterTransition = {
                                        fadeIn(animationSpec = tween(300))
                                    },
                                    exitTransition = {
                                        fadeOut(animationSpec = tween(300))
                                    }
                                ) {
                                    SearchScreen(
                                        onBack = { 
                                            navController.navigate("home") {
                                                popUpTo("home") { inclusive = true }
                                            }
                                        },
                                        onNavigateToDetail = { uri: String ->
                                            navController.navigate("detail?bookUri=${Uri.encode(uri)}")
                                        }
                                    )
                                }
                                composable("detail?bookUri={bookUri}") { backStackEntry ->
                                    val bookUri = backStackEntry.arguments?.getString("bookUri")
                                    val selectedBook = audiobooks.find { it.uri == bookUri }
                                    
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
                                        onBackClick = { navController.popBackStack() },
                                        onPlayClick = { 
                                            selectedBook?.let { book ->
                                                playerViewModel.loadMedia(
                                                    uri = book.uri.toUri(), 
                                                    title = book.title, 
                                                    author = book.author,
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
                                        fadeIn(animationSpec = tween(400, easing = FastOutSlowInEasing)) +
                                        androidx.compose.animation.slideInVertically(
                                            initialOffsetY = { it / 2 },
                                            animationSpec = tween(400, easing = FastOutSlowInEasing)
                                        )
                                    },
                                    exitTransition = {
                                        fadeOut(animationSpec = tween(400, easing = FastOutSlowInEasing)) +
                                        androidx.compose.animation.slideOutVertically(
                                            targetOffsetY = { it / 2 },
                                            animationSpec = tween(400, easing = FastOutSlowInEasing)
                                        )
                                    }
                                ) {
                                    PlayerScreen(
                                        onMinimize = { navController.popBackStack() },
                                        isPlaying = isPlaying,
                                        title = currentTitle,
                                        author = currentAuthor,
                                        coverPath = currentCoverPath,
                                        currentPosition = { currentPositionState.value },
                                        duration = { durationState.value },
                                        chapters = currentChapters,
                                        onSeek = { pos -> playerViewModel.seekTo(pos) },
                                        onSkipForward = { playerViewModel.skipForward() },
                                        onSkipBackward = { playerViewModel.skipBackward() },
                                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                                        playbackSpeed = playbackSpeed,
                                        onSpeedChange = { speed -> playerViewModel.setPlaybackSpeed(speed) },
                                        selectedSleepTimer = selectedSleepTimer,
                                        onSleepTimerChange = { mins -> playerViewModel.setSleepTimer(mins) },
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this@composable
                                    )
                                }
                            }

                            // 迷你播放器
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showMiniPlayer,
                                enter = fadeIn(animationSpec = tween(400, easing = FastOutSlowInEasing)),
                                exit = fadeOut(animationSpec = tween(400, easing = FastOutSlowInEasing)) +
                                       androidx.compose.animation.slideOutVertically(
                                           targetOffsetY = { it },
                                           animationSpec = tween(400, easing = FastOutSlowInEasing)
                                       ),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                            ) {
                                Box(modifier = Modifier.clickable { navController.navigate("player") }) {
                                    CompactMediaPlayer(
                                        isPlaying = isPlaying,
                                        title = currentTitle,
                                        author = currentAuthor,
                                        coverPath = currentCoverPath,
                                        progress = {
                                            val d = durationState.value
                                            if (d > 0) currentPositionState.value.toFloat() / d.toFloat() else 0f
                                        },
                                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this@AnimatedVisibility
                                    )
                                }
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
