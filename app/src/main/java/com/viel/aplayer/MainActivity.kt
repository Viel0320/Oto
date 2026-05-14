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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.viel.aplayer.ui.components.CompactMediaPlayer
import com.viel.aplayer.ui.action.MiniPlayerActions
import com.viel.aplayer.ui.action.PlayerActions
import com.viel.aplayer.ui.action.PlayerNavigationActions
import com.viel.aplayer.ui.screens.DetailScreen
import com.viel.aplayer.ui.screens.HomeScreen
import com.viel.aplayer.ui.screens.PlayerContentScreen
import com.viel.aplayer.ui.screens.PlayerScreen
import com.viel.aplayer.ui.screens.SearchScreen
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.utils.formatFileSize
import com.viel.aplayer.ui.utils.formatTime
import com.viel.aplayer.ui.viewmodel.LibraryViewModel
import com.viel.aplayer.ui.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            APlayerTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val context = LocalContext.current
                val libraryViewModel: LibraryViewModel = viewModel()
                val playerViewModel: PlayerViewModel = viewModel()
                val libraryUiState by libraryViewModel.uiState.collectAsState()

                LaunchedEffect(Unit) {
                    playerViewModel.initialize(context)
                }

                LaunchedEffect(currentRoute) {
                    playerViewModel.onRouteChanged()
                }
                
                val playerUiState by playerViewModel.uiState.collectAsState()
                val playerActions = remember(playerViewModel) {
                    PlayerActions(
                        onSeek = { pos, allowUndo -> playerViewModel.seekTo(pos, allowUndo) },
                        onUndoSeek = { playerViewModel.undoSeek() },
                        onDeleteBookmark = { bookmark -> playerViewModel.deleteBookmark(bookmark) },
                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                        onSkipForward = { playerViewModel.skipForward() },
                        onSkipBackward = { playerViewModel.skipBackward() },
                        onCyclePlaybackSpeed = { playerViewModel.cyclePlaybackSpeed() },
                        onResetPlaybackSpeed = { playerViewModel.resetPlaybackSpeed() },
                        onCycleSleepTimer = { playerViewModel.cycleSleepTimer() },
                        onCancelSleepTimer = { playerViewModel.setSleepTimer(0) },
                        onSelectedContentTabChange = { playerViewModel.setSelectedContentTab(it) },
                        onShowChapterList = { playerViewModel.showChapterList() },
                        onDismissChapterList = { playerViewModel.dismissChapterList() },
                        onShowBookmarkDialog = { playerViewModel.showBookmarkDialog() },
                        onDismissBookmarkDialog = { playerViewModel.dismissBookmarkDialog() },
                        onBookmarkTitleChange = { playerViewModel.updateBookmarkTitle(it) },
                        onSaveBookmark = { playerViewModel.saveBookmarkFromDialog() }
                    )
                }
                val miniPlayerActions = remember(playerViewModel) {
                    MiniPlayerActions(
                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                        onHide = { playerViewModel.setMiniPlayerHidden(true) }
                    )
                }
                val playerNavigationActions = remember(navController) {
                    PlayerNavigationActions(
                        onMinimize = { navController.popBackStack() },
                        onClose = { navController.popBackStack() },
                        onBookmarksClick = { navController.navigate("content/0") },
                        onSubtitlesClick = { navController.navigate("content/1") },
                        onRelatedClick = { navController.navigate("content/2") }
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val showMiniPlayer = currentRoute != "player" && 
                                             currentRoute != "content/{tab}" && 
                                             currentRoute != "search" &&
                                             playerUiState.hasActiveTrack

                        NavHost(
                            navController = navController,
                            startDestination = "home",
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable("home") {
                                HomeScreen(
                                    audiobooks = libraryUiState.audiobooks,
                                    isMiniPlayerVisible = playerUiState.hasActiveTrack,
                                    onNavigateToDetail = { uri: String ->
                                        navController.navigate("detail?bookUri=${Uri.encode(uri)}")
                                    },
                                    onNavigateToSearch = {
                                        navController.navigate("search")
                                    },
                                    onLoadMedia = { uri: Uri, title: String, author: String, narrator: String, pos: Long -> 
                                        playerViewModel.loadMedia(uri, title, author, narrator, pos) 
                                    },
                                    onNavigateToPlayer = { navController.navigate("player") },
                                    onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) }
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
                                val selectedBook = libraryUiState.audiobooks.find { it.uri == bookUri }
                                LaunchedEffect(selectedBook) {
                                    libraryViewModel.selectDetailBook(selectedBook)
                                }
                                val detailUiState by libraryViewModel.detailUiState.collectAsState()
                                val detailBook = detailUiState.book

                                DetailScreen(
                                    title = detailBook?.title ?: "Unknown Title",
                                    author = detailBook?.author ?: "Unknown Author",
                                    narrator = detailBook?.narrator ?: "Unknown Narrator",
                                    description = detailBook?.description ?: "",
                                    coverPath = detailBook?.coverPath,
                                    duration = formatTime(detailBook?.duration ?: 0L),
                                    year = if (!detailBook?.year.isNullOrBlank()) detailBook.year else "Unknown",
                                    fileSize = formatFileSize(detailBook?.fileSize ?: 0L),
                                    progressPercent = detailUiState.progressPercent,
                                    isAvailable = detailUiState.isAvailable,
                                    backgroundColorArgb = detailUiState.backgroundColorArgb,
                                    onBackClick = { navController.popBackStack() },
                                    onPlayClick = { 
                                        detailBook?.let { book ->
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
                                PlayerScreen(
                                    uiState = playerUiState,
                                    actions = playerActions,
                                    navigationActions = playerNavigationActions
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
                                LaunchedEffect(tab) {
                                    playerViewModel.setSelectedContentTab(tab)
                                }
                                PlayerContentScreen(
                                    uiState = playerUiState,
                                    actions = playerActions,
                                    navigationActions = playerNavigationActions
                                )
                            }
                        }

                        // Mini player
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showMiniPlayer && !playerUiState.isMiniPlayerHidden,
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
                                    isPlaying = playerUiState.isPlaying,
                                    title = playerUiState.currentTitle,
                                    author = playerUiState.currentAuthor,
                                    narrator = playerUiState.currentNarrator,
                                    coverPath = playerUiState.currentThumbnailPath ?: playerUiState.currentCoverPath,
                                    progress = {
                                        playerUiState.progress
                                    },
                                    actions = miniPlayerActions
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}
