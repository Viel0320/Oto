package com.viel.aplayer.ui.navigation

// Setup Haze Backdrop (Enhance Blur Visuals)
// Import Haze's backdrop mechanism API to completely replace the legacy blur library dependency, achieving a clearer viewport-level Gaussian blur refraction effect.
// Setup Haze Core (Import dev.chrisbanes.haze modifiers) Import HazeState and haze modifier for Compose-based blur.
// Theme Mode Selection (Support theme mode preference settings) Added ThemeMode import to access selected theme configurations.
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.VisualEffectPolicy
import com.viel.aplayer.ui.common.theme.rememberVisualEffectEnvironment
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailRoute
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.edit.EditBookRoute
import com.viel.aplayer.ui.edit.EditBookViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.miniplayer.MiniPlayerActions
import com.viel.aplayer.ui.miniplayer.MiniPlayerOverlay
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.navigation.shell.DefaultAppFeedbackRenderer
import com.viel.aplayer.ui.navigation.shell.dispatch
import com.viel.aplayer.ui.player.PlayerOverlay
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.search.SearchRoute
import com.viel.aplayer.ui.search.SearchViewModel
import com.viel.aplayer.ui.settings.SettingsDialogHost
import com.viel.aplayer.ui.settings.SettingsDialogState
import com.viel.aplayer.ui.settings.SettingsOverlay
import com.viel.aplayer.ui.settings.SettingsViewModel
import com.viel.aplayer.ui.settings.rememberSettingsDialogController
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
    val appShellDependencies = remember(context) {
        // App Shell Dependency Resolution (Resolve only settings and app-event stream for the top-level shell)
        // The navigation host renders global feedback and theme state without needing screen-specific or playback dependencies.
        com.viel.aplayer.APlayerApplication.getAppShellDependencies(context)
    }
    val settingsRepository = remember(appShellDependencies) {
        appShellDependencies.settingsRepository
    }
    val appEventSink = remember(appShellDependencies) {
        appShellDependencies.appEventSink
    }
    val appFeedbackRenderer = remember {
        DefaultAppFeedbackRenderer
    }
    // Async Settings Load (Use collectAsStateWithLifecycle to load AppSettings, seeding it with the pre-cached value)
    // Avoids running a blocking runBlocking read on the main thread during cold start, preventing thread lock/ANR.
    val initialSettings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = settingsRepository.cachedSettings
    )

    val requestedGlassEffectMode = if (libraryUiState.selectedFilter != null) {
        libraryUiState.glassEffectMode
    } else {
        initialSettings.glassEffectMode
    }
    val visualEffectEnvironment = rememberVisualEffectEnvironment()
    val activeGlassEffectMode = VisualEffectPolicy.resolveGlassEffectMode(
        requestedMode = requestedGlassEffectMode,
        environment = visualEffectEnvironment
    )

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

    // Localized Resource Context (Feed app-language choices into Compose resource lookup)
    // Android 13+ may already apply the platform locale, while Android 12L receives the same language through a configuration context fallback.
    val effectiveAppLanguage = AppLocaleController.resolveEffectiveLanguage(context, initialSettings.appLanguage)
    val localizedContext = remember(context, effectiveAppLanguage) {
        AppLocaleController.wrapContext(context, effectiveAppLanguage)
    }
    val localizedConfiguration = localizedContext.resources.configuration
    // Activity Host Owner Capture (Read Activity-provided owners before LocalContext becomes a locale wrapper)
    // APlayerApp is hosted by MainActivity, so missing owners indicate an invalid host rather than an optional app state.
    val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "APlayerApp requires an ActivityResultRegistryOwner host."
    }
    val onBackPressedDispatcherOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
        "APlayerApp requires an OnBackPressedDispatcherOwner host."
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "APlayerApp requires a ViewModelStoreOwner host."
    }
    val savedStateRegistryOwner = LocalSavedStateRegistryOwner.current

    CompositionLocalProvider(
        // Activity Owner Preservation (Keep Activity-scoped Compose services available after swapping LocalContext)
        // The localized configuration context is not an Activity, so registry, lifecycle, back handling, ViewModel, and saved-state owners must be carried forward explicitly.
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        LocalOnBackPressedDispatcherOwner provides onBackPressedDispatcherOwner,
        LocalLifecycleOwner provides lifecycleOwner,
        LocalViewModelStoreOwner provides viewModelStoreOwner,
        LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration
    ) {
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
        val absConnectionState by settingsViewModel.absConnectionState.collectAsStateWithLifecycle()
        val webDavConnectionState by settingsViewModel.webDavConnectionState.collectAsStateWithLifecycle()
        // Home Add Library Dialog Controller (Reuse Settings modal state outside the Settings overlay)
        // The empty Home FAB opens the same SettingsDialogHost add-library flow without showing the full settings page or duplicating source-specific forms.
        val homeAddLibraryDialogController = rememberSettingsDialogController()
        val homeAddLibraryRootLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri?.let { selectedRoot ->
                // Home SAF Root Submission (Route local folder registration through SettingsViewModel)
                // Using the settings command path keeps Home FAB behavior aligned with Settings add-library and avoids restoring the old direct SAF-only import shortcut.
                settingsViewModel.onLibraryRootSelected(selectedRoot)
            }
        }

        val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
        // Collect Detail UI State (Coordinate route interactions without rebinding mini player glass)
        // Detail visibility still drives Search route handoff, while MiniPlayerOverlay intentionally keeps the stable app-level HazeState to avoid effect flashes.
        val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
        /*
         * Detail Transition Gate Adapter (Bind generic gating to Detail selection)
         *
         * The gate owns only idle and queued-request ordering; this adapter keeps DetailViewModel
         * mutation and overlay-start detection inside the app shell where the Detail state is known.
         */
        val detailTransitionGate = rememberTransitionGate<DetailOpenRequest>(
            detailViewModel
        ) { request ->
            val startsOverlayTransition = request.book != null && !detailViewModel.uiState.value.isVisible
            detailViewModel.selectBook(
                book = request.book,
                entrySource = request.entrySource
            )
            startsOverlayTransition
        }

        // Collect Search Visibility State (Control MiniPlayer Rendering)
        // Responsively collect the visibility state flow of the Search route to control mini-player mounting under layer occlusion.
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
                // Widget Direct Open (Bypass mini-player transition channels)
                // External widget requests do not originate from an on-screen mini player, so the
                // full player must open directly on the main playback surface without shared mini motion.
                playerViewModel.openFullPlayerFromDirect()
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

        // App Event Collection (Single app-shell renderer for transient feedback)
        // Consumes process-wide AppShellEvent values so Home, Settings, scan, ABS sync, and playback no longer expose parallel event streams.
        // Localized Feedback Dispatch (Resolve transient feedback through the same context used by Compose)
        // Toast resources follow the in-app language on Android 12L because the renderer now receives the localized configuration context instead of the Activity base context.
        LaunchedEffect(appEventSink, appFeedbackRenderer, playerViewModel, localizedContext) {
            appEventSink.events.collect { event ->
                appFeedbackRenderer.render(event).dispatch(
                    context = localizedContext,
                    onTrackUnavailableDialog = playerViewModel::showTrackUnavailableDialog
                )
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
                        glassEffectMode = activeGlassEffectMode,
                        // Home View Preference State (Route current renderer and sort selections to the NavHost-owned top bar dialog)
                        // HomeScreen still renders the catalog from LibraryUiState, while the app bar dialog only edits these persisted preferences.
                        homeViewStyle = libraryUiState.homeViewStyle,
                        homeSortRule = libraryUiState.homeSortRule,
                        // Home Sort Direction State (Route in-cluster sort direction into the NavHost-owned preference dialog)
                        // The Home catalog policy keeps cluster order fixed while this value controls ascending or descending comparisons inside clusters.
                        homeSortDirection = libraryUiState.homeSortDirection,
                        // Home Book Status Filter State (Route availability filter into the NavHost-owned preference dialog)
                        // The shell forwards the value only; LibraryViewModel remains responsible for applying the filter to Home catalog data.
                        homeBookStatusFilter = libraryUiState.homeBookStatusFilter,
                        // Home Dialog Haze Routing (Use the same app backdrop source as Search route)
                        // Passing the top-level source prevents Home dialogs from sampling the clipped LazyGrid-local fallback source.
                        homeDialogHazeState = hazeState,
                        // Settings Navigation Callback (To open settings overlay on request)
                        // Binds setting launch request event to change settings overlay visibility.
                        onNavigateToSettings = {
                            settingsViewModel.setVisible(true)
                        },
                        onAddLibraryRequested = {
                            // Home Empty-State Add Library Entry (Open Settings source-type picker in place)
                            // This keeps the existing Home FAB affordance while reusing the Settings add-library dialog and all provider-specific follow-up logic.
                            homeAddLibraryDialogController.dialogState = SettingsDialogState.AddLibraryType
                        },
                        onEditBookRequested = { bookId ->
                            // Home Action Menu Edit Entry (Open the edit overlay from the selected catalog row)
                            // Home and Detail both route shared action-dialog edit intents into the same app-owned EditBookViewModel lifecycle.
                            editViewModel.startEdit(bookId)
                        },
                        onOpenDetail = detailTransitionGate::request,
                        onHomeViewStyleSelected = libraryViewModel::setHomeViewStyle,
                        onHomeSortRuleSelected = libraryViewModel::setHomeSortRule,
                        onHomeSortDirectionSelected = libraryViewModel::setHomeSortDirection,
                        onHomeBookStatusFilterSelected = libraryViewModel::setHomeBookStatusFilter
                    )
                }

                // Mount Detail Route with HazeStates (Link background and overlay blur targets)
                // Route owns ViewModel/effect wiring while its overlay shell owns animation and haze registration.
                DetailRoute(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    glassEffectMode = activeGlassEffectMode,
                    hazeState = hazeState,
                    detailHazeState = detailHazeState,
                    onPlayBook = { bookId ->
                        playerViewModel.loadBook(bookId)
                        // Detail Direct Playback Open (Avoid stale mini-player source reuse)
                        // Detail playback starts from the detail command surface, so the player opens
                        // through the direct path until a dedicated detail->player transition exists.
                        playerViewModel.openFullPlayerFromDirect()
                    },
                    onNavigateToSearch = { query ->
                        searchViewModel.setVisible(true)
                        searchViewModel.onQueryChange(TextFieldValue(query))
                    },
                    onEditBookRequested = { bookId ->
                        // Detail Action Menu Edit Entry (Open the edit overlay from the selected detail projection)
                        // Detail now owns a shared action dialog, while the app shell keeps EditBookViewModel lifecycle and route ownership centralized.
                        editViewModel.startEdit(bookId)
                    },
                    onUpdateReadStatus = { bookId, status ->
                        // Detail Action Menu Read Status Update (Reuse the library scene command path)
                        // Manual status changes from Detail should emit the same persistence and feedback behavior as Home action-menu updates.
                        libraryViewModel.updateBookReadStatus(bookId, status)
                    },
                    onForceRegenerate = { bookId ->
                        // Detail Action Menu Metadata Refresh (Reuse the library scene regeneration command)
                        // The Detail dialog only supplies the selected id; cover and metadata rebuilding remain behind LibraryViewModel.
                        libraryViewModel.forceRegenerateCoverAndMetadata(bookId)
                    },
                    onDeleteBook = { bookId ->
                        // Detail Action Menu Delete Coordination (Close playback and dismiss Detail before library removal)
                        // Keeping cleanup in the app shell avoids duplicating destructive side effects inside the Detail UI or shared dialog component.
                        playerViewModel.closePlayback(bookId)
                        detailViewModel.dismissIfShowing(bookId)
                        libraryViewModel.deleteBook(bookId)
                    },
                    onTransitionIdleChanged = detailTransitionGate::onTransitionIdleChanged
                )

                // MiniPlayer Stable Haze Target (Keep the effect state constant across overlays)
                // Detail route registers its visible content into this same app-level source, so the mini player can sample Detail without rebinding its own HazeEffect and flashing during transitions.
                val targetHazeState = hazeState

                // Mount MiniPlayer with HazeState (Provide blur context to mini player overlay) Passed target HazeState value to match active background visuals.
                MiniPlayerOverlay(
                    playerViewModel = playerViewModel,
                    miniPlayerActions = miniPlayerActions,
                    isSearchActive = isSearchVisible,
                    glassEffectMode = activeGlassEffectMode,
                    hazeState = targetHazeState
                )

                // Edit Overlay Stable Haze Target (Keep edit glass bound to app-level sampling)
                // The edit sheet behaves like other app overlays, so it uses the same long-lived app HazeState instead of rebinding to Detail's local source.
                EditBookRoute(
                    editViewModel = editViewModel,
                    glassEffectMode = activeGlassEffectMode,
                    hazeState = hazeState,
                    onSaveSuccess = {
                        // Edit Save Success (Reactive Flow Refresh)
                        // After saving successfully, the responsive flow will automatically refresh and redraw the details page via the Room Flow, so there is no need to execute extra UI dirty operations to force a refresh.
                    }
                )

                // PlayerOverlay Mounting (Layer Decoupling Hierarchy)
                // Core hierarchy change: To ensure that the full-screen player completely covers the mini-player, full-screen editing interface, and details page when expanded, PlayerOverlay is declared after EditBookRoute.
                PlayerOverlay(
                    playerViewModel = playerViewModel,
                    playerActions = playerActions,
                    playerNavigationActions = playerNavigationActions,
                    glassEffectMode = activeGlassEffectMode,
                    // Player App-Level Haze Registration (Let expanded player become the active sampled surface)
                    // PlayerOverlay registers its full-screen content into this stable source so Search and dialogs can sample the visible player instead of stale route content.
                    appHazeState = hazeState
                )

                // Search Stable Haze Target (Keep SearchRoute bound to the app-level sampler)
                // Visible pages now register themselves into this same source, so Search no longer switches state when Detail or Player becomes the background.
                SearchRoute(
                    searchViewModel = searchViewModel,
                    hazeState = hazeState,
                    glassEffectMode = activeGlassEffectMode,
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
                        detailUiState.book?.bookId
                    } else {
                        null
                    },
                    onNavigateToDetail = { bookId ->
                        val book = libraryUiState.audiobooks.find { it.id == bookId }?.let { libraryBook ->
                            // Search Detail Boundary Mapping (Convert the search-selected library row into a Detail scene item)
                            // The navigation shell bridges the Home scene projection to Detail without exposing Room rows through DetailViewModel.
                            DetailBookItem(
                                id = libraryBook.id,
                                rootId = libraryBook.rootId,
                                sourceType = libraryBook.sourceType,
                                title = libraryBook.title,
                                author = libraryBook.author,
                                narrator = libraryBook.narrator,
                                description = libraryBook.description,
                                year = libraryBook.year,
                                totalDurationMs = libraryBook.totalDurationMs,
                                totalFileSize = libraryBook.totalFileSize,
                                coverPath = libraryBook.coverPath,
                                thumbnailPath = libraryBook.thumbnailPath,
                                lastScannedAt = libraryBook.lastScannedAt,
                                progressPercent = libraryBook.progressPercent,
                                // Search Detail Read Status Projection (Preserve status when opening Detail from search results)
                                // The Detail action dialog derives its payload from DetailBookItem, so search-to-detail mapping must carry the same readStatus field as Home.
                                readStatus = libraryBook.readStatus
                            )
                        }
                        detailTransitionGate.request(
                            DetailOpenRequest(
                                book = book,
                                /*
                                 * Search Detail Entry Source (Search motion channel tagging)
                                 *
                                 * Marks this selection as Search-originated so Detail binds to the
                                 * search2detail cover key instead of any Home artwork channel.
                                 */
                                entrySource = DetailEntrySource.Search
                            ),
                            beforeExecute = {
                                /*
                                 * Search Source Lifetime (Close search only when the handoff starts)
                                 *
                                 * If Detail re-entry is queued behind an exit animation, keeping Search
                                 * composed preserves the selected result thumbnail as the source endpoint.
                                 */
                                searchViewModel.setVisible(false)
                            }
                        )
                    },
                    onLoadBook = { bookId ->
                        searchViewModel.setVisible(false)
                        playerViewModel.loadBook(bookId)
                    },
                    onNavigateToPlayer = {
                        // Search Direct Playback Open (Keep search playback outside mini motion)
                        // Search currently owns a dedicated Search->Detail transition only; direct
                        // playback should open the full player without claiming the mini source.
                        playerViewModel.openFullPlayerFromDirect()
                    }
                )

                // Settings Stable Dialog Haze Target (Share app-level sampling with settings-owned dialogs)
                // Settings keeps local page chrome sampling separately, while its dialogs use the stable app source like Search and playback dialogs.
                SettingsOverlay(
                    settingsViewModel = settingsViewModel,
                    glassEffectMode = activeGlassEffectMode,
                    appHazeState = hazeState
                )

                // Home Add Library Dialog Host (Share Settings add-library dialogs with the empty Home FAB)
                // Hosting this beside the Settings overlay lets Home open the source-type picker directly while reusing SAF, WebDAV, and Audiobookshelf form handling from SettingsDialogHost.
                SettingsDialogHost(
                    controller = homeAddLibraryDialogController,
                    glassEffectMode = activeGlassEffectMode,
                    settingsDialogHazeState = if (activeGlassEffectMode == GlassEffectMode.Haze) hazeState else null,
                    appLanguage = effectiveAppLanguage,
                    onAppLanguageChange = { settingsViewModel.updateAppLanguage(it) },
                    webDavConnectionState = webDavConnectionState,
                    onWebDavConnectionTest = { url, username, password, basePath, editingRootId ->
                        settingsViewModel.testWebDavConnection(url, username, password, basePath, editingRootId)
                    },
                    onResetWebDavConnectionState = {
                        settingsViewModel.resetWebDavConnectionState()
                    },
                    onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                        settingsViewModel.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                    },
                    onWebDavRootUpdated = { id, url, username, password, displayName, basePath ->
                        settingsViewModel.updateWebDavRoot(id, url, username, password, displayName, basePath)
                    },
                    absConnectionState = absConnectionState,
                    onAbsConnectionTest = { baseUrl, username, password, editingRootId ->
                        settingsViewModel.testAbsConnection(baseUrl, username, password, editingRootId)
                    },
                    onResetAbsConnectionState = {
                        settingsViewModel.resetAbsConnectionState()
                    },
                    onAbsRootSubmitted = { baseUrl, username, password, libraryId, libraryName, editingRootId ->
                        settingsViewModel.addAbsServerWithPassword(baseUrl, username, password, libraryId, libraryName, editingRootId)
                    },
                    getWebDavCredentials = { credentialId ->
                        settingsViewModel.getWebDavCredentials(credentialId)
                    },
                    getAbsCredential = { credentialId ->
                        settingsViewModel.getAbsCredential(credentialId)
                    },
                    onAbsSync = { rootId -> settingsViewModel.syncAbsRoot(rootId) },
                    onRescan = { settingsViewModel.triggerRescan() },
                    onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
                    onLaunchSafRootPicker = { homeAddLibraryRootLauncher.launch(null) }
                )

                // Track Unavailable Confirm Dialog (Avoid Interruptions)
                // Secondary confirmation dialog for track unavailability, shown only when the full-screen player is expanded (isFullPlayerVisible) to prevent interrupting user interaction on other screens.
                val trackUnavailableState by playerViewModel.trackUnavailableDialogState.collectAsStateWithLifecycle()
                // ABS Progress Conflict Dialog State (Surface server-vs-device resume choices at the app shell)
                // The dialog remains hosted near other one-shot player dialogs while all conflict resolution commands stay inside PlayerViewModel.
                val absProgressConflictState by playerViewModel.absProgressConflictDialogState.collectAsStateWithLifecycle()
                APlayerAppDialogHost(
                    hazeState = hazeState,
                    glassEffectMode = activeGlassEffectMode,
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
}
