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
import com.viel.aplayer.ui.action.MiniPlayerActions
import com.viel.aplayer.ui.action.PlayerActions
import com.viel.aplayer.ui.action.PlayerNavigationActions
import com.viel.aplayer.ui.components.CompactMediaPlayer
import com.viel.aplayer.ui.screens.DetailScreen
import com.viel.aplayer.ui.screens.HomeScreen
import com.viel.aplayer.ui.screens.NewPlayerScreen
import com.viel.aplayer.ui.screens.SearchScreen
import com.viel.aplayer.ui.theme.APlayerTheme
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
                        onUpdateBookmark = { bookmark, newTitle -> playerViewModel.updateBookmark(bookmark, newTitle) },
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
                        onSaveBookmark = { playerViewModel.saveBookmarkFromDialog() },
                        onToggleProgressMode = { playerViewModel.toggleProgressMode() },
                        onAdjustVolume = { delta -> playerViewModel.adjustVolume(delta) },
                        onNextChapter = { playerViewModel.skipToNextChapter() },
                        onPreviousChapter = { playerViewModel.skipToPreviousChapter() },
                        onLoadRelatedBook = { book ->
                            playerViewModel.loadMedia(
                                uri = book.uri.toUri(),
                                title = book.title,
                                author = book.author,
                                narrator = book.narrator,
                                startPositionMs = book.lastPosition,
                                playWhenReady = true
                            )
                        }
                    )
                }
                val miniPlayerActions = remember(playerViewModel) {
                    MiniPlayerActions(
                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                        onHide = { playerViewModel.setMiniPlayerHidden(true) }
                    )
                }
                val playerNavigationActions = remember(navController, playerViewModel) {
                    PlayerNavigationActions(
                        onMinimize = { navController.popBackStack() },
                        onClose = { navController.popBackStack() },
                        onBookmarksClick = { 
                            playerViewModel.setSelectedContentTab(0)
                            navController.navigate("player/0") 
                        },
                        onSubtitlesClick = { 
                            playerViewModel.setSelectedContentTab(1)
                            navController.navigate("player/1") 
                        },
                        onRelatedClick = { 
                            playerViewModel.setSelectedContentTab(2)
                            navController.navigate("player/2") 
                        },
                        onNavigateToNewPlayer = {} // 已整合，置空
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val showMiniPlayer = currentRoute?.startsWith("player") == false &&
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
                                    selectedFilter = libraryUiState.selectedFilter,
                                    onFilterSelected = { libraryViewModel.setFilter(it) },
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
                                    onNavigateToPlayer = { navController.navigate("player/-1") },
                                    onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) }
                                )
                            }
                            composable(
                                "search?q={q}",
                                enterTransition = { fadeIn(animationSpec = tween(400)) },
                                exitTransition = { fadeOut(animationSpec = tween(400)) },
                                popEnterTransition = { fadeIn(animationSpec = tween(400)) },
                                popExitTransition = { fadeOut(animationSpec = tween(400)) }
                            ) { backStackEntry ->
                                val initialQuery = backStackEntry.arguments?.getString("q")
                                SearchScreen(
                                    initialQuery = initialQuery,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToDetail = { uri: String ->
                                        navController.navigate("detail?bookUri=${Uri.encode(uri)}")
                                    },
                                    onLoadMedia = { uri: Uri, title: String, author: String, narrator: String, pos: Long -> 
                                        playerViewModel.loadMedia(uri, title, author, narrator, pos) 
                                    },
                                    onNavigateToPlayer = { navController.navigate("player/-1") }
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

                                DetailScreen(
                                    uiState = detailUiState,
                                    onBackClick = { navController.popBackStack() },
                                    onSearchClick = { query ->
                                        navController.navigate("search?q=${Uri.encode(query)}")
                                    },
                                    onPlayClick = { 
                                        detailUiState.book?.let { book ->
                                            playerViewModel.loadMedia(
                                                uri = book.uri.toUri(), 
                                                title = book.title, 
                                                author = book.author,
                                                narrator = book.narrator,
                                                startPositionMs = book.lastPosition
                                            )
                                        }
                                        navController.navigate("player/-1") 
                                    }
                                )
                            }

                            composable(
                                "player/{tab}",
                                enterTransition = {
                                    if (initialState.destination.route?.startsWith("player") == true) {
                                        fadeIn(animationSpec = tween(400))
                                    } else {
                                        slideInVertically(initialOffsetY = { it }, animationSpec = tween(400))
                                    }
                                },
                                exitTransition = {
                                    if (targetState.destination.route?.startsWith("player") == true) {
                                        fadeOut(animationSpec = tween(400))
                                    } else {
                                        slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                                    }
                                },
                                popEnterTransition = { fadeIn(animationSpec = tween(400)) },
                                popExitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) }
                            ) { backStackEntry ->
                                val tab = backStackEntry.arguments?.getString("tab")?.toIntOrNull() ?: -1
                                LaunchedEffect(tab) {
                                    playerViewModel.setSelectedContentTab(tab)
                                }
                                NewPlayerScreen(
                                    uiState = playerUiState,
                                    actions = playerActions,
                                    navigationActions = playerNavigationActions
                                )
                            }
                        }

                        // Mini player
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showMiniPlayer && !playerUiState.isMiniPlayerHidden,
                            enter = slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = tween(400)
                            ) + fadeIn(animationSpec = tween(400)),
                            exit = slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = tween(400)
                            ) + fadeOut(animationSpec = tween(400)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                        ) {
                            Box(modifier = Modifier.clickable { navController.navigate("player/-1") }) {
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
