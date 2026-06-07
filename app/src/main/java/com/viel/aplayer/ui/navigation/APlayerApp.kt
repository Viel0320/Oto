package com.viel.aplayer.ui.navigation

// Setup Haze Backdrop (Enhance Blur Visuals)
// Import Haze's backdrop mechanism API to completely replace the legacy blur library dependency, achieving a clearer viewport-level Gaussian blur refraction effect.
// Setup Haze Core (Import dev.chrisbanes.haze modifiers) Import HazeState and haze modifier for Compose-based blur.
// Theme Mode Selection (Support theme mode preference settings) Added ThemeMode import to access selected theme configurations.
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.viel.aplayer.data.store.ThemeMode
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
import com.viel.aplayer.ui.settings.SettingsOverlay
import com.viel.aplayer.ui.settings.SettingsViewModel
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun APlayerApp(
    openPlayerOverlayRequest: Boolean = false,
    onOpenPlayerOverlayConsumed: () -> Unit = {}
) {
    val libraryViewModel: LibraryViewModel = viewModel()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val settingsRepository = remember {
        com.viel.aplayer.APlayerApplication.getContainer(context).settingsRepository
    }
    // Async Settings Load (Use collectAsStateWithLifecycle to load AppSettings, seeding it with the pre-cached value)
    // Avoids running a blocking runBlocking read on the main thread during cold start, preventing thread lock/ANR.
    val initialSettings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = settingsRepository.cachedSettings
    )

    val activeGlassEffectMode = if (libraryUiState.selectedFilter != null) {
        libraryUiState.glassEffectMode
    } else {
        initialSettings.glassEffectMode
    }

    val activeThemeMode = if (libraryUiState.selectedFilter != null) {
        libraryUiState.themeMode
    } else {
        initialSettings.themeMode
    }

    // Resolve Dynamic Color Preference (Calculate active dynamic color state based on setting and system configuration) Load dynamic color setting state dynamically.
    val isDynamicColorEnabled = if (libraryUiState.selectedFilter != null) {
        libraryUiState.isDynamicColorEnabled
    } else {
        initialSettings.isDynamicColorEnabled
    }

    // Resolve Theme Selection (Calculate target darkTheme state based on setting and glass effect mode)
    // Compute active dark theme state dynamically. If Haze mode is active, override and force dark theme.
    val isDarkTheme = if (activeGlassEffectMode == GlassEffectMode.Haze) {
        true
    } else {
        when (activeThemeMode) {
            ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    }

    // Apply App Theme (Pass active dark theme and dynamic color states down to the customized theme container) Wraps the layout in APlayerTheme.
    APlayerTheme(
        darkTheme = isDarkTheme,
        dynamicColor = isDynamicColorEnabled
    ) {
        // Setup Navigation 3
        // Controller (Initialize state and navigator) Migrate from rememberNavController to custom NavigationState.
        val navigationState = rememberNavigationState(
            startRoute = HomeRoute,
            topLevelRoutes = setOf(HomeRoute)
        )
        val navigator = remember { Navigator(navigationState) }
        val currentRoute = navigationState.topLevelRoute

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

        // SettingsViewModel Lifecycle (Host Lifecycle Management)
        // Instantiate the settings ViewModel hosted and destroyed by MainActivity.
        val settingsViewModel: SettingsViewModel = viewModel()

        val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
        // Collect Detail UI State (Coordinate overlay routing without rebinding mini player glass)
        // Detail visibility still drives SearchOverlay routing and shared transition source handoff, while MiniPlayerOverlay intentionally keeps the stable app-level HazeState to avoid effect flashes.
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
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    // Global Haze State Provider: Provide hazeState via LocalHazeState CompositionLocal.
                    // Details: Expose the app-level hazeState globally so all nested dialogs can sample from it without explicit parameter prop drilling.
                    com.viel.aplayer.ui.common.theme.LocalHazeState provides hazeState
                ) {
                    // Box Layer Decoupling (Prevent Recursive Deadlock Rendering)
                    // Use a full-screen top-level Box container at the outermost layer without mounting layerBackdrop, solely as a coordinate alignment and sibling node layout container for all overlays. This completely isolates the layerBackdrop sampling source layer, avoiding infinite recursion deadlock rendering failure due to overlays using textureBlur to sample their own parent containers internally.
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                // Navigation Host Layer (Delegate route-level haze source ownership)
                // APlayerNavHost mounts the active route content as the Haze source so HomeAppBar can be rendered as a sibling overlay above that source.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                        searchViewModel = searchViewModel,
                        // NavHost Haze Source Mount (Let the navigation host own route-level sampling)
                        // This lets HomeAppBar live as a sibling overlay above NavDisplay while still sampling the route content.
                        appHazeState = hazeState,
                        glassEffectMode = libraryUiState.glassEffectMode,
                        // Home View Preference State (Route current renderer and sort selections to the NavHost-owned top bar dialog)
                        // HomeScreen still renders the catalog from LibraryUiState, while the app bar dialog only edits these persisted preferences.
                        homeViewStyle = libraryUiState.homeViewStyle,
                        homeSortRule = libraryUiState.homeSortRule,
                        // Home Dialog Haze Routing (Use the same app backdrop source as SearchOverlay)
                        // Passing the top-level source prevents Home dialogs from sampling the clipped LazyGrid-local fallback source.
                        homeDialogHazeState = hazeState,
                        // Settings Navigation Callback (To open settings overlay on request)
                        // Binds setting launch request event to change settings overlay visibility.
                        onNavigateToSettings = {
                            settingsViewModel.setVisible(true)
                        },
                        onHomeViewStyleSelected = libraryViewModel::setHomeViewStyle,
                        onHomeSortRuleSelected = libraryViewModel::setHomeSortRule
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

                // MiniPlayer Stable Haze Target (Keep the effect state constant across overlays)
                // DetailOverlay registers its visible content into this same app-level source, so the mini player can sample Detail without rebinding its own HazeEffect and flashing during transitions.
                val targetHazeState = hazeState

                // Mount MiniPlayer with HazeState (Provide blur context to mini player overlay) Passed target HazeState value to match active background visuals.
                MiniPlayerOverlay(
                    playerViewModel = playerViewModel,
                    miniPlayerActions = miniPlayerActions,
                    isSearchActive = isSearchVisible,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    hazeState = targetHazeState
                )

                // Edit Overlay Stable Haze Target (Keep edit glass bound to app-level sampling)
                // The edit sheet behaves like other app overlays, so it uses the same long-lived app HazeState instead of rebinding to Detail's local source.
                EditBookOverlay(
                    editViewModel = editViewModel,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    hazeState = hazeState,
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
                    glassEffectMode = libraryUiState.glassEffectMode,
                    // Player App-Level Haze Registration (Let expanded player become the active sampled surface)
                    // PlayerOverlay registers its full-screen content into this stable source so Search and dialogs can sample the visible player instead of stale route content.
                    appHazeState = hazeState
                )

                // Search Stable Haze Target (Keep SearchOverlay bound to the app-level sampler)
                // Visible pages now register themselves into this same source, so Search no longer switches state when Detail or Player becomes the background.
                SearchOverlay(
                    searchViewModel = searchViewModel,
                    hazeState = hazeState,
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

                // Settings Stable Dialog Haze Target (Share app-level sampling with settings-owned dialogs)
                // Settings keeps local page chrome sampling separately, while its dialogs use the stable app source like Search and playback dialogs.
                SettingsOverlay(
                    settingsViewModel = settingsViewModel,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    appHazeState = hazeState
                )

                // Track Unavailable Confirm Dialog (Avoid Interruptions)
                // Secondary confirmation dialog for track unavailability, shown only when the full-screen player is expanded (isFullPlayerVisible) to prevent interrupting user interaction on other screens.
                val trackUnavailableState by playerViewModel.trackUnavailableDialogState.collectAsStateWithLifecycle()
                // ABS Progress Conflict Dialog State (Surface server-vs-device resume choices at the app shell)
                // The dialog remains hosted near other one-shot player dialogs while all conflict resolution commands stay inside PlayerViewModel.
                val absProgressConflictState by playerViewModel.absProgressConflictDialogState.collectAsStateWithLifecycle()
                APlayerAppDialogHost(
                    hazeState = hazeState,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    isFullPlayerVisible = playerUiState.isFullPlayerVisible,
                    absProgressConflictState = absProgressConflictState,
                    trackUnavailableState = trackUnavailableState,
                    onDismissAbsProgressConflict = { playerViewModel.dismissAbsProgressConflictDialog() },
                    onAcceptRemoteAbsProgressConflict = { playerViewModel.acceptRemoteAbsProgressConflict() },
                    onAcceptLocalAbsProgressConflict = { playerViewModel.acceptLocalAbsProgressConflict() },
                    onDismissTrackUnavailable = { playerViewModel.dismissTrackUnavailableDialog() },
                    onSkipToNextAvailableTrack = { bookId, queueIndex ->
                        // Skip to Next Available (Trigger ViewModel self-healing from the app-level dialog host)
                        // Keeps the concrete playback mutation in PlayerViewModel while APlayerAppDialogHost owns the confirmation UI.
                        playerViewModel.skipToNextAvailableTrack(bookId, queueIndex)
                    }
                )
            }
        }
    }
}
}
}

