package com.viel.aplayer.ui.navigation

// Setup MiuixBlur Backdrop (Enhance Blur Visuals)
// Import miuix-blur's backdrop mechanism API to completely replace the legacy blur library dependency, achieving a clearer viewport-level Gaussian blur refraction effect.
// Setup Haze Core (Import dev.chrisbanes.haze modifiers) Import HazeState and haze modifier for Compose-based blur.
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.ScanResultDialog
import com.viel.aplayer.ui.common.UiEvent
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailOverlay
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.edit.EditBookOverlay
import com.viel.aplayer.ui.edit.EditBookViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.miniplayer.MiniPlayerActions
import com.viel.aplayer.ui.miniplayer.MiniPlayerOverlay
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.components.PlayerOverlay
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.search.SearchOverlay
import com.viel.aplayer.ui.search.SearchViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun APlayerApp(
    openPlayerOverlayRequest: Boolean = false,
    onOpenPlayerOverlayConsumed: () -> Unit = {}
) {
    APlayerTheme {
        // Setup Navigation 3 Controller (Initialize state and navigator) Migrate from rememberNavController to custom NavigationState.
        val navigationState = rememberNavigationState(
            startRoute = HomeRoute,
            topLevelRoutes = setOf(HomeRoute)
        )
        val navigator = remember { Navigator(navigationState) }
        val currentRoute = navigationState.topLevelRoute

        val context = LocalContext.current
        val libraryViewModel: LibraryViewModel = viewModel()
        val playerViewModel: PlayerViewModel = viewModel()
        // Separation of DetailViewModel (Single Responsibility)
        // Independent ViewModel for the audiobook details page, split from LibraryViewModel to make each ViewModel have a single responsibility.
        val detailViewModel: DetailViewModel = viewModel()
        // EditBookViewModel Lifecycle (Host Lifecycle Management)
        // Instantiate the independent ViewModel for editing book metadata, which is hosted and destroyed by the current Activity.
        val editViewModel: EditBookViewModel = viewModel()
        
        // SearchViewModel Lifecycle (Host Lifecycle Management)
        // Instantiate the non-independent SearchViewModel, hosted and destroyed by the current Activity.
        val searchViewModel: SearchViewModel = viewModel()

        val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
        val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
        val scanResult by libraryViewModel.scanResultDialogState.collectAsStateWithLifecycle()
        // Collect Detail UI State (Adapt MiuixBlur Sampling Source)
        // Collect the detailUiState from detailViewModel here. This is used when rendering the MiniPlayer overlay to perceive whether the details page is visible, so as to dynamically map the miuix-blur sampling source.
        val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

        // Collect Search Visibility State (Control MiniPlayer Rendering)
        // Responsively collect the visibility state flow of the non-independent SearchOverlay, used to dynamically determine and control the display and mounting of the mini-player, avoiding invalid rendering and resource waste under layer occlusion.
        val isSearchVisible by searchViewModel.isVisible.collectAsStateWithLifecycle()

        val canStartNavigation = rememberNavigationThrottle()

        // Setup HazeStates (Manage blur states using Haze library) Introduce global hazeState and detailHazeState for blurring backgrounds.
        val hazeState = remember { HazeState() }
        val detailHazeState = remember { HazeState() }

        LaunchedEffect(Unit) {
            playerViewModel.initialize(context)
        }

        // Process Widget Intent (Trigger Full Player Overlay)
        // Consume the external request passed from MainActivity via the desktop app widget, immediately switching back to the main playback page and showing the full-screen player overlay.
        LaunchedEffect(openPlayerOverlayRequest) {
            if (openPlayerOverlayRequest) {
                playerViewModel.setSelectedContentTab(-1)
                playerViewModel.setMiniPlayerHidden(false)
                playerViewModel.setFullPlayerVisible(true)
                onOpenPlayerOverlayConsumed()
            }
        }

        LaunchedEffect(currentRoute) {
            playerViewModel.onRouteChanged()
        }


        // Fix M-19 (High-Frequency Progress Sync)
        // Observe the player's current playing book ID and real-time playback progress percentage. Once they change, immediately call detailViewModel.updatePlaybackProgress to push high-frequency updates into the detail ViewModel, which will coordinate updates with a 3-second locking mechanism.
        val currentBookId by playerViewModel.currentBookId.collectAsStateWithLifecycle()
        val playbackPercent by playerViewModel.currentPlaybackProgressPercent.collectAsStateWithLifecycle()
        LaunchedEffect(currentBookId, playbackPercent) {
            currentBookId?.let { bookId ->
                if (playbackPercent > 0) {
                    detailViewModel.updatePlaybackProgress(bookId, playbackPercent)
                }
            }
        }

        // Toast Notification Collection (Decouple Toast Construction)
        // Consume the one-time UI events (such as Toast messages) emitted by LibraryViewModel, adhering to the architecture principle that ViewModels do not directly manipulate Android UI components. The construction and display of all Toasts are handled in the Composable layer. Refactored to centrally render matching common UiEvent.ShowToast events.
        LaunchedEffect(Unit) {
            libraryViewModel.uiEvents.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> {
                        val spannable = SpannableString(event.message)
                        spannable.setSpan(
                            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                            0, event.message.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        Toast.makeText(context, spannable, Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        // Player UI Event Collection (Native Toast Presentation)
        // Consume the one-time UI feedback events shared and forwarded by the player's PlayerViewModel. Present messages using standard Toast directly, avoiding over-packaging to maintain a native and concise style.
        LaunchedEffect(Unit) {
            playerViewModel.uiEvents.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> {
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    }
                    is UiEvent.ShowTrackUnavailableDialog -> {
                        // Track Unavailable Event (Trigger Confirmatory Dialog)
                        // Upon receiving the track unavailable event, immediately update the state in the ViewModel to trigger the Compose confirmation and jump dialog.
                        playerViewModel.showTrackUnavailableDialog(event.bookId, event.queueIndex)
                    }
                }
            }
        }

        // Setup Back Navigation (Hook up Nav3 back logic) Migrate back callback to use Navigation 3 Navigator's goBack API.
        val navigateBack: () -> Unit = remember(navigator) {
            {
                if (canStartNavigation()) {
                    navigator.goBack()
                }
            }
        }

        // Build Action Objects (Logical Decoupling & Performance Caching)
        // Use extension functions to construct the Actions object, achieving logical decoupling and performance caching.
        val playerActions = playerViewModel.rememberActions(
            onDeleteBook = { bookId ->
                playerViewModel.closePlayback(bookId)
                // Dismiss Detail Page (State Cleanup Coordination)
                // Explicitly coordinate the cleanup of the details page state, keeping it consistent with the outer coordination mode of playerViewModel.closePlayback.
                detailViewModel.dismissIfShowing(bookId)
                libraryViewModel.deleteBook(bookId)
                // Evaluate Navigation Stack State (Check current route stack) Verify if current route is not HomeRoute before popping back.
                if (currentRoute != HomeRoute) {
                    navigateBack()
                }
            }
        )

        val miniPlayerActions = remember(playerViewModel) {
            MiniPlayerActions(
                onPlayPauseClick = { playerViewModel.togglePlayPause() },
                onHide = { playerViewModel.setMiniPlayerHidden(true) },
                onUnavailable = { playerViewModel.closeCurrentPlayback() }
            )
        }
        // Build Player Navigation Actions (Migrate callback hooks) Removed unused NavController dependency in favor of direct visibility state toggles.
        val playerNavigationActions = remember(playerViewModel) {
            PlayerNavigationActions(
                onMinimize = { playerViewModel.setFullPlayerVisible(false) },
                onClose = { playerViewModel.setFullPlayerVisible(false) },
                onBookmarksClick = {
                    playerViewModel.setSelectedContentTab(0)
                    playerViewModel.setFullPlayerVisible(true)
                },
                onSubtitlesClick = {
                    playerViewModel.setSelectedContentTab(1)
                    playerViewModel.setFullPlayerVisible(true)
                },
                onRelatedClick = {
                    playerViewModel.setSelectedContentTab(2)
                    playerViewModel.setFullPlayerVisible(true)
                },
                onNavigateToNewPlayer = { playerViewModel.setFullPlayerVisible(true) }
            )
        }

        // Outer Surface Layout (Avoid Keyboard Inset Clipping)
        // Restore the cleanest top-level Surface layout to completely avoid the truncation and leakage of the floating height and background sampling layer when forced to consume bottom padding due to the keyboard popping up.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            /*
             * Establish Shared Element Layout Context (Shared element boundary setup)
             * Encapsulate overlay views within a SharedTransitionLayout and provide the transition scope
             * via LocalSharedTransitionScope to enable smooth bounds morphing and cover transformations.
             */
            SharedTransitionLayout(
                modifier = Modifier.fillMaxSize()
            ) {
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout
                ) {
                    // Box Layer Decoupling (Prevent Recursive Deadlock Rendering)
                    // Use a full-screen top-level Box container at the outermost layer without mounting layerBackdrop, solely as a coordinate alignment and sibling node layout container for all overlays. This completely isolates the layerBackdrop sampling source layer, avoiding infinite recursion deadlock rendering failure due to overlays using textureBlur to sample their own parent containers internally.
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                // Mount Haze Backdrop (Isolate overlay blur source) Replace miuix-blur backdrop with native Haze modifier on bottom navigation Box.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // Align to Haze settings mode
                            if (libraryUiState.glassEffectMode == GlassEffectMode.Haze) {
                                Modifier.hazeSource(hazeState)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // System Navigation Container (Scaffold Insets Handling)
                    // The bottom-most system navigation management container, the main navigation page (HomeScreen contains its own local Scaffold to provide its own bottom insets avoidance).
                    // Setup Navigation 3 Host (Inject Nav3 state parameters) Injected NavigationState and Navigator into APlayerNavHost.
                    APlayerNavHost(
                        navigationState = navigationState,
                        navigator = navigator,
                        libraryViewModel = libraryViewModel,
                        playerViewModel = playerViewModel,
                        detailViewModel = detailViewModel,
                        canStartNavigation = canStartNavigation,
                        navigateBack = navigateBack,
                        searchViewModel = searchViewModel
                    )
                }

                // Mount DetailOverlay with HazeStates (Link background and overlay blur targets) Replaced App/Detail Backdrop with corresponding hazeState and detailHazeState parameters.
                DetailOverlay(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    hazeState = hazeState,
                    detailHazeState = detailHazeState,
                    onPlayBook = { bookId ->
                        playerViewModel.loadBook(bookId)
                        playerViewModel.setFullPlayerVisible(true)
                    },
                    onNavigateToSearch = { query ->
                        searchViewModel.setVisible(true)
                        searchViewModel.onQueryChange(TextFieldValue(query))
                    },
                    // Handle Edit Click (Launch EditBookOverlay)
                    // Receive the book editing click event from the detail page, and directly launch the EditBookOverlay floating layer inside the memory without delay.
                    onEditClick = { bookId ->
                        editViewModel.startEdit(bookId)
                    }
                )

                // Connect HazeState Source (Switch blur reference source for MiniPlayer)
                // Dynamically select target HazeState depending on the visibility of the detail page.
                // When DetailOverlay is visible, sample from detailHazeState, otherwise sample from home page hazeState.
                val targetHazeState = if (detailUiState.isVisible) detailHazeState else hazeState

                // Mount MiniPlayer with HazeState (Provide blur context to mini player overlay) Passed target HazeState value to match active background visuals.
                MiniPlayerOverlay(
                    playerViewModel = playerViewModel,
                    miniPlayerActions = miniPlayerActions,
                    isSearchActive = isSearchVisible,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    hazeState = targetHazeState
                )

                // Mount EditBookOverlay with HazeState (Provide detail background blur context to edit book overlay) Passed detailHazeState to EditBookOverlay.
                EditBookOverlay(
                    editViewModel = editViewModel,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    hazeState = detailHazeState,
                    onSaveSuccess = {
                        // Edit Save Success (Reactive Flow Refresh)
                        // After saving successfully, the responsive flow will automatically refresh and redraw the details page via the Room Flow, so there is no need to execute extra UI dirty operations to force a refresh.
                    }
                )

                // PlayerOverlay Mounting (Layer Decoupling Hierarchy)
                // Core hierarchy change: To ensure that the full-screen player completely covers the mini-player, full-screen editing interface, and details page when expanded, the physical declaration position of PlayerOverlay is moved after EditBookOverlay in the Box container.
                PlayerOverlay(
                    playerViewModel = playerViewModel,
                    playerActions = playerActions,
                    playerNavigationActions = playerNavigationActions,
                    glassEffectMode = libraryUiState.glassEffectMode
                )

                // Mount SearchOverlay with HazeState (Provide global background blur context to search overlay)
                // Dynamically pass hazeState or detailHazeState to SearchOverlay depending on whether the detail screen is visible.
                SearchOverlay(
                    searchViewModel = searchViewModel,
                    hazeState = if (detailUiState.isVisible) detailHazeState else hazeState,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    /*
                     * Search Detail Source Selector (Search-result source handoff)
                     *
                     * Activates only when Detail was opened from Search so the selected search
                     * result thumbnail can exit without touching Home recent or Home list sources.
                     */
                    activeSearchDetailBookId = if (
                        detailUiState.isVisible &&
                        detailUiState.entrySource == DetailEntrySource.Search
                    ) {
                        detailUiState.book?.book?.id
                    } else {
                        null
                    },
                    onNavigateToDetail = { bookId ->
                        searchViewModel.setVisible(false)
                        val book = libraryUiState.audiobooks.find { it.book.id == bookId }
                        detailViewModel.selectBook(
                            book = book,
                            /*
                             * Search Detail Entry Source (Search motion channel tagging)
                             *
                             * Marks this selection as Search-originated so Detail binds to the
                             * search2detail cover key instead of any Home artwork channel.
                             */
                            entrySource = DetailEntrySource.Search
                        )
                    },
                    onLoadBook = { bookId ->
                        searchViewModel.setVisible(false)
                        playerViewModel.loadBook(bookId)
                    },
                    onNavigateToPlayer = {
                        playerViewModel.setFullPlayerVisible(true)
                    }
                )

                // Scan Result Dialog (Display Scan Summary)
                // Dialog panel for showing QR/barcode scanning results.
                scanResult?.let { session ->
                    ScanResultDialog(
                        session = session,
                        onDismiss = { libraryViewModel.dismissScanResultDialog() }
                    )
                }

                // Track Unavailable Confirm Dialog (Avoid Interruptions)
                // Secondary confirmation dialog for track unavailability, shown only when the full-screen player is expanded (isFullPlayerVisible) to prevent interrupting user interaction on other screens.
                val trackUnavailableState by playerViewModel.trackUnavailableDialogState.collectAsStateWithLifecycle()
                if (trackUnavailableState.show && playerUiState.isFullPlayerVisible) {
                    AlertDialog(
                        onDismissRequest = { playerViewModel.dismissTrackUnavailableDialog() },
                        title = { Text("分轨文件不可用") },
                        text = { Text("当前收听的分轨物理文件不存在或损坏。是否跳过该分轨并播放下一首可用分轨？\n\n（注意：强制跳轨可能会打乱您原本预定的收听进度）") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    // Skip to Next Available (Trigger ViewModel Self-Healing)
                                    // The user confirms skipping the current unavailable track, calling the ViewModel interface to trigger self-healing.
                                    playerViewModel.skipToNextAvailableTrack(
                                        trackUnavailableState.bookId,
                                        trackUnavailableState.queueIndex
                                    )
                                    playerViewModel.dismissTrackUnavailableDialog()
                                }
                            ) {
                                Text("确认跳过", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { playerViewModel.dismissTrackUnavailableDialog() }
                            ) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }
}
}
}
