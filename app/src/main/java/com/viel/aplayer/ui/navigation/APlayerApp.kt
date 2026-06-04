package com.viel.aplayer.ui.navigation

// Setup MiuixBlur Backdrop (Enhance Blur Visuals)
// Import miuix-blur's backdrop mechanism API to completely replace the legacy blur library dependency, achieving a clearer viewport-level Gaussian blur refraction effect.
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.ScanResultDialog
import com.viel.aplayer.ui.common.UiEvent
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.detail.DetailOverlay
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.edit.EditBookOverlay
import com.viel.aplayer.ui.edit.EditBookViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.miniplayer.MiniPlayerActions
import com.viel.aplayer.ui.miniplayer.MiniPlayerOverlay
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.components.PlayerOverlay
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.search.SearchOverlay
import com.viel.aplayer.ui.search.SearchViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@Composable
fun APlayerApp(
    openPlayerOverlayRequest: Boolean = false,
    onOpenPlayerOverlayConsumed: () -> Unit = {}
) {
    APlayerTheme {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

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

        // Global App Backdrop (Live Backdrop Blur)
        // Instantiate a globally shared LayerBackdrop sampling source. When the user globally enables the frosted glass effect, the entire APlayerNavHost container will be mounted as the sampling source, allowing the MiniPlayer (CompactMediaPlayer) and the non-independent SearchOverlay floating above it to directly and highly performantly blur the HomeScreen main page cards below, eliminating any system desktop leak risks caused by cross-activity blurs.
        val appBackdrop = rememberLayerBackdrop()

        // Detail Backdrop Source (Detail Page Blur Backdrop)
        // Instantiate the detailBackdrop sampling source specifically for capturing the visual rendering of the entire details page (DetailOverlay). When the EditBookOverlay pops up over the details page, it can render a highly sophisticated frosted glass visual backdrop using detailBackdrop to blend the text, cover cards, and buttons on the details page.
        val detailBackdrop = rememberLayerBackdrop()

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

        val navigateBack: () -> Unit = remember(navController) {
            {
                if (canStartNavigation() && navController.previousBackStackEntry != null) {
                    navController.popBackStack()
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
                if (currentRoute != null && currentRoute != "home") {
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
        val playerNavigationActions = remember(navController, playerViewModel) {
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
            // Box Layer Decoupling (Prevent Recursive Deadlock Rendering)
            // Use a full-screen top-level Box container at the outermost layer without mounting layerBackdrop, solely as a coordinate alignment and sibling node layout container for all overlays. This completely isolates the layerBackdrop sampling source layer, avoiding infinite recursion deadlock rendering failure due to overlays using textureBlur to sample their own parent containers internally.
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // NavHost Backdrop Mounting (Isolate Overlay Blur Source)
                // Use a separate Box container specifically wrapping the bottom APlayerNavHost (where the HomeScreen main page cards reside), and mount Modifier.layerBackdrop(state = appBackdrop) on this Box. Thus, when the user enables the miuix-blur effect in global settings, the appBackdrop sampling source only captures the screen data of the main navigation page, while the DetailOverlay, PlayerOverlay, and SearchOverlay above exist as sibling nodes outside it. This achieves full Z-axis occlusion and Gaussian blur rendering while breaking the drawing deadlock caused by "child sampling parent", restoring the frosted glass effect perfectly.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // Align to MiuixBlur settings mode
                            if (libraryUiState.glassEffectMode == GlassEffectMode.MiuixBlur) {
                                Modifier.layerBackdrop(appBackdrop)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // System Navigation Container (Scaffold Insets Handling)
                    // The bottom-most system navigation management container, the main navigation page (HomeScreen contains its own local Scaffold to provide its own bottom insets avoidance).
                    APlayerNavHost(
                        navController = navController,
                        libraryViewModel = libraryViewModel,
                        playerViewModel = playerViewModel,
                        detailViewModel = detailViewModel,
                        canStartNavigation = canStartNavigation,
                        navigateBack = navigateBack,
                        searchViewModel = searchViewModel
                    )
                }

                // DetailOverlay Mounting (Search overlay coupling and EditBook integration)
                // The DetailOverlay is now mounted as a sibling node outside layerBackdrop to avoid blur deadlock issues. Completely deprecates the original intent-based contract for launching SearchActivity, using an in-memory Lambda synchronous bridge: when the search button on the book details page is clicked, the non-independent SearchOverlay is awakened without delay inside the same Activity, and the initial query is passed synchronously to quickly search for related authors/narrators.
                DetailOverlay(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    // Pass App Backdrop (Apply Glass Effect)
                    // Pass the global appBackdrop to the detail page to achieve the same frosted glass refraction effect as the background.
                    backdrop = appBackdrop,
                    // Pass Detail Backdrop (Register Capture Source)
                    // Pass the exclusive detailBackdrop to the detail page, letting it register as the source of that sampling source to capture the full detail page screen.
                    detailBackdrop = detailBackdrop,
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

                // Z-index Level Adjustments (Precise Overlay Draw Order)
                // Precisely reorder the drawing sequence of all overlay components based on the physical hierarchy specification inside the main Activity window. In the Jetpack Compose Box container, components declared later have higher physical rendering and interaction priority (higher Z-index):
                // 1. Bottom APlayerNavHost (declared above)
                // 2. DetailOverlay (declared above)
                // 3. MiniPlayerOverlay floating above the details page
                // 4. EditBookOverlay (full-screen edit interface) completely covering the detail page and mini-player
                // 5. PlayerOverlay (full-screen player) completely covering the mini-player, edit layer, and detail page when expanded
                // 6. SearchOverlay (top layer) covering the full-screen player and all other content.

                // MiniPlayer Backdrop Source (Adaptive Blur Source Strategy)
                // Core blur sampling source adaptive repair and delay strategy:
                // - When DetailOverlay is visible (detailUiState.isVisible is true), the physical layer below the mini-player is actually the details page, so we dynamically switch its backdrop to detailBackdrop, allowing the frosted glass to display the background color of the details page realistically.
                // - When the details page is hidden, safely switch back to appBackdrop to sample the HomeScreen interface.
                // Adaptive delay switching strategy for sampling source is used for the detail page visibility changes to smooth the transition animation.
                val targetBackdrop = if (detailUiState.isVisible) detailBackdrop else appBackdrop
                val delayedBackdropState = remember(appBackdrop, detailBackdrop) {
                    mutableStateOf(targetBackdrop)
                }
                LaunchedEffect(targetBackdrop) {
                    val delayMs = 401L
                    delay(delayMs)
                    delayedBackdropState.value = targetBackdrop
                }

                MiniPlayerOverlay(
                    playerViewModel = playerViewModel,
                    miniPlayerActions = miniPlayerActions,
                    isSearchActive = isSearchVisible,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    // Use Delayed Backdrop (Avoid Transition Flicker)
                    // Use the delayed sampling source to avoid transition animation persistence and flickering, enhancing the ultimate feeling of smoothness.
                    backdrop = delayedBackdropState.value
                )

                // EditBookOverlay Mounting (Frosted Glass Translucency)
                // Core hierarchy change: To ensure the full-screen editing overlay is displayed above the mini-player (covering both the details page and the mini-player), it is declared after MiniPlayerOverlay in the Box container. Meanwhile, the dedicated detailBackdrop is used to provide an exquisite frosted glass translucent texture.
                EditBookOverlay(
                    editViewModel = editViewModel,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    backdrop = detailBackdrop,
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

                // SearchOverlay Mounting (Top-most Rendering Layer)
                // Core hierarchy change: To ensure the search function runs as a global top-level container, covering the full-screen player and all other contents, we place it after PlayerOverlay in the physical Z-axis declaration, giving it the highest physical rendering level except for the scan results.
                SearchOverlay(
                    searchViewModel = searchViewModel,
                    backdrop = appBackdrop,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    onNavigateToDetail = { bookId ->
                        searchViewModel.setVisible(false)
                        val book = libraryUiState.audiobooks.find { it.book.id == bookId }
                        detailViewModel.selectBook(book)
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
