package com.viel.aplayer.ui.navigation

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
import com.viel.aplayer.application.library.home.toDetailBookItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.VisualEffectPolicy
import com.viel.aplayer.ui.common.theme.rememberVisualEffectEnvironment
import com.viel.aplayer.ui.common.uiPerformanceTrace
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailRoute
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.edit.EditBookRoute
import com.viel.aplayer.ui.edit.EditBookViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.motion.LocalSharedTransitionScope
import com.viel.aplayer.ui.navigation.shell.DefaultAppFeedbackRenderer
import com.viel.aplayer.ui.navigation.shell.dispatch
import com.viel.aplayer.ui.player.BookmarkViewModel
import com.viel.aplayer.ui.player.PlaybackViewModel
import com.viel.aplayer.ui.player.PlayerOverlay
import com.viel.aplayer.ui.player.PlayerSettingsViewModel
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.search.SearchRoute
import com.viel.aplayer.ui.search.SearchViewModel
import com.viel.aplayer.ui.settings.SettingsDialogHost
import com.viel.aplayer.ui.settings.SettingsDialogState
import com.viel.aplayer.ui.settings.SettingsOverlay
import com.viel.aplayer.ui.settings.SettingsViewModel
import com.viel.aplayer.ui.settings.rememberSettingsDialogController
import dev.chrisbanes.haze.HazeState

/**
 * Hosts the top-level app shell and resolves appearance preferences before navigation is rendered.
 *
 * Theme mode is resolved independently from the selected glass effect mode so Haze can use light,
 * dark, or system appearance normally. Dynamic color remains owned by APlayerTheme and continues
 * to use the same wallpaper seed path for both Material and Haze rendering.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun APlayerApp(
    openPlayerOverlayRequest: Boolean = false,
    onOpenPlayerOverlayConsumed: () -> Unit = {},
    openDownloadManagementRequest: Boolean = false,
    onOpenDownloadManagementConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application

    val libraryViewModel: LibraryViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                // Initialize LibraryViewModel (Inject application into ViewModel factory context)
                // Supplies the application context explicitly to construct LibraryViewModel safely.
                return LibraryViewModel(application) as T
            }
        }
    )
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

    // Cache Mapped Detail Items (Optimize navigation mapping performance across recompositions)
    // Converts the list of home audiobooks to detail projections only when the list changes, avoiding redundant mapping in lambda execution or on every recomposition.
    val detailBookItems = remember(libraryUiState.audiobooks) {
        libraryUiState.audiobooks.associate { it.id to it.toDetailBookItem() }
    }

    val appShellDependencies = remember(context) {
        // App Shell Dependency Resolution (Resolve only settings and app-event stream for the top-level shell)
        // The navigation host renders global feedback and theme state without needing screen-specific or playback dependencies.
        com.viel.aplayer.APlayerApplication.getAppShellDependencies(context)
    }
    // Title: Settings Read Model Binding (Bind APlayerApp to settingsReadModel abstraction)
    // Decouples navigation shell from concrete data repository classes.
    val settingsReadModel = remember(appShellDependencies) {
        appShellDependencies.settingsReadModel
    }
    val appEventSink = remember(appShellDependencies) {
        appShellDependencies.appEventSink
    }
    val appFeedbackRenderer = remember {
        DefaultAppFeedbackRenderer
    }
    // Async Settings Load (Use collectAsStateWithLifecycle to load AppSettings, seeding it with the pre-cached value)
    // Avoids running a blocking runBlocking read on the main thread during cold start, preventing thread lock/ANR.
    val initialSettings by settingsReadModel.settingsFlow.collectAsStateWithLifecycle(
        initialValue = settingsReadModel.cachedSettings
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

    // Resolve AMOLED Dark Theme Preference (Live-preview from library state while in settings, else persisted value)
    val isAmoledEnabled = if (libraryUiState.selectedFilter != null) {
        libraryUiState.isAmoledEnabled
    } else {
        initialSettings.isAmoledEnabled
    }

    // Resolve Theme Selection (Calculate target darkTheme state based on setting)
    val isDarkTheme = when (activeThemeMode) {
        ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
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
        // Apply App Theme (Pass active dark theme and dynamic color states down to the theme container)
        APlayerTheme(
            darkTheme = isDarkTheme,
            dynamicColor = isDynamicColorEnabled,
            amoled = isAmoledEnabled
        ) {
        // Setup Navigation 3
        // Controller (Initialize state and navigator) Migrate from rememberNavController to custom NavigationState.
        val navigationState = rememberNavigationState(
            startRoute = HomeRoute,
            topLevelRoutes = setOf(HomeRoute)
        )
        val navigator = remember { Navigator(navigationState) }
        val currentRoute = navigationState.topLevelRoute

        val playbackViewModel: PlaybackViewModel = viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                // Title: Suppress unchecked cast for PlaybackViewModel (Allows casting ViewModel inside provider factory without warning)
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    // Title: Instantiate PlaybackViewModel without Composable scope (Avoids rotation freeze and lifecycle leaks)
                    // Configures PlaybackViewModel with only application dependency so it can safely use viewModelScope by default.
                    return PlaybackViewModel(application) as T
                }
            }
        )
        val bookmarkViewModel: BookmarkViewModel = viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                // Title: Suppress unchecked cast for BookmarkViewModel (Allows casting ViewModel inside provider factory without warning)
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    // Title: Instantiate BookmarkViewModel without Composable scope (Avoids rotation freeze and lifecycle leaks)
                    // Configures BookmarkViewModel with only application dependency so it can safely use viewModelScope by default.
                    return BookmarkViewModel(application) as T
                }
            }
        )
        val playerSettingsViewModel: PlayerSettingsViewModel = viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                // Title: Suppress unchecked cast for PlayerSettingsViewModel (Allows casting ViewModel inside provider factory without warning)
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    // Title: Instantiate PlayerSettingsViewModel without Composable scope (Avoids rotation freeze and lifecycle leaks)
                    // Configures PlayerSettingsViewModel with only application dependency so it can safely use viewModelScope by default.
                    return PlayerSettingsViewModel(application) as T
                }
            }
        )
        // Bind Playback Seek Undo Callback (Orchestrate playback seek state updates with settings state flow)
        // Description: Establishes a link where seek-triggered visibility updates in the playback controller are propagated directly into the settings manager to show/hide the undo snackbar.
        LaunchedEffect(playbackViewModel, playerSettingsViewModel) {
            playbackViewModel.onUndoSeekVisibilityChanged = { visible ->
                playerSettingsViewModel.setUndoSeekVisible(visible)
            }
        }
        // Separation of DetailViewModel (Single Responsibility)
        // Independent ViewModel for the audiobook details page, split from LibraryViewModel to make each ViewModel have a single responsibility.
        val detailViewModel: DetailViewModel = viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                // Title: Initialize DetailViewModel with Custom Factory (Ensures dependencies are correctly provided and avoids default empty constructor lookup)
                // Description: Explicitly constructs DetailViewModel with the active application instance.
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return DetailViewModel(application) as T
                }
            }
        )
        // EditBookViewModel Lifecycle (Host Lifecycle Management)
        // Instantiate the independent ViewModel for editing book metadata, which is hosted and destroyed by the current Activity.
        val editViewModel: EditBookViewModel = viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                // Title: Initialize EditBookViewModel with Custom Factory (Ensures dependencies are correctly provided and avoids default empty constructor lookup)
                // Description: Explicitly constructs EditBookViewModel with the active application instance.
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return EditBookViewModel(application) as T
                }
            }
        )
        
        // SearchViewModel Lifecycle (Host Lifecycle Management)
        // Instantiate the non-independent SearchViewModel, hosted and destroyed by the current Activity.
        val searchViewModel: SearchViewModel = viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                // Title: Initialize SearchViewModel with Custom Factory (Ensures dependencies are correctly provided and avoids default empty constructor lookup)
                // Description: Explicitly constructs SearchViewModel with the active application instance.
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return SearchViewModel(application) as T
                }
            }
        )

        // SettingsViewModel Lifecycle (Host Lifecycle Management)
        // Instantiate the settings ViewModel hosted and destroyed by MainActivity.
        val settingsViewModel: SettingsViewModel = viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                // Title: Initialize SettingsViewModel with Custom Factory (Ensures dependencies are correctly provided and avoids default empty constructor lookup)
                // Description: Explicitly constructs SettingsViewModel with the active application instance.
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(application) as T
                }
            }
        )
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

        val playerSettings by playerSettingsViewModel.settingsState.collectAsStateWithLifecycle()
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


        // Title: Disable bottom layer culling to support Haze blur backdrop sampling
        // Description: Cleaned up bottom layer rendering culling variables so that background screens remain fully drawn while overlays are active. This preserves proper backdrop sampling for Haze blurs and prevents blank space exposures.

        val canStartNavigation = rememberNavigationThrottle()

        // App Haze Source (Stable sampler for route content and cross-route overlays)
        // Detail intentionally registers back into this shell-owned HazeState, while Settings and full-player page internals keep their private samplers.
        val hazeState = remember { HazeState() }



        // Process Widget Intent (Trigger Full Player Overlay)
        // Consume the external request passed from MainActivity via the desktop app widget, immediately switching back to the main playback page and showing the full-screen player overlay.
        LaunchedEffect(openPlayerOverlayRequest) {
            if (openPlayerOverlayRequest) {
                // Widget Direct Open (Bypass mini-player transition channels)
                // External widget requests do not originate from an on-screen mini player, so the
                // full player must open directly on the main playback surface without shared mini motion.
                playerSettingsViewModel.openFullPlayerFromDirect()
                onOpenPlayerOverlayConsumed()
            }
        }

        // Process Download Notification Intent (Open settings before routing to the task-management sub-page)
        // SettingsOverlay owns local settings sub-page navigation, while the app shell only makes sure the overlay is visible.
        LaunchedEffect(openDownloadManagementRequest) {
            if (openDownloadManagementRequest) {
                settingsViewModel.setVisible(true)
            }
        }

        LaunchedEffect(currentRoute) {
            playerSettingsViewModel.onRouteChanged()
        }


        // Fix M-19 (High-Frequency Progress Sync)
        // Observe the player's current playing book ID and real-time playback progress percentage. Once they change, immediately call detailViewModel.updatePlaybackProgress to push high-frequency updates into the detail ViewModel, which will coordinate updates with a 3-second locking mechanism.
        val currentBookId by playbackViewModel.currentBookId.collectAsStateWithLifecycle()
        val playbackPercent by playbackViewModel.currentPlaybackProgressPercent.collectAsStateWithLifecycle()
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
        LaunchedEffect(appEventSink, appFeedbackRenderer, playbackViewModel, localizedContext) {
            appEventSink.events.collect { event ->
                appFeedbackRenderer.render(event).dispatch(
                    context = localizedContext,
                    onTrackUnavailableDialog = playbackViewModel::showTrackUnavailableDialog
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
        val playerActions = rememberActions(
            playbackViewModel = playbackViewModel,
            bookmarkViewModel = bookmarkViewModel,
            settingsViewModel = playerSettingsViewModel,
            onDeleteBook = { bookId ->
                playbackViewModel.closePlayback(bookId)
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

        // Relocate mini-player actions
        // MiniPlayerActions is now instantiated directly inside PlayerOverlay, removing duplicate creation logic from the app shell.
        // Build Player Navigation Actions (Migrate callback hooks) Removed unused NavController dependency in favor of direct visibility state toggles.
        val playerNavigationActions = remember(playbackViewModel, playerSettingsViewModel) {
            PlayerNavigationActions(
                onMinimize = { playerSettingsViewModel.setFullPlayerVisible(false) },
                onClose = { playerSettingsViewModel.setFullPlayerVisible(false) },
                onBookmarksClick = {
                    playerSettingsViewModel.setSelectedContentTab(0)
                    playerSettingsViewModel.setFullPlayerVisible(true)
                },
                onSubtitlesClick = {
                    playerSettingsViewModel.setSelectedContentTab(1)
                    playerSettingsViewModel.setFullPlayerVisible(true)
                },
                onRelatedClick = {
                    playerSettingsViewModel.setSelectedContentTab(2)
                    playerSettingsViewModel.setFullPlayerVisible(true)
                },
                onNavigateToNewPlayer = { playerSettingsViewModel.setFullPlayerVisible(true) }
            )
        }

        // Root Trace State (Summarize only app-shell routing and visibility state)
        // The trace avoids user-authored content and keeps global diagnostics focused on route, theme, and overlay churn.
        val appTraceState = "route=$currentRoute,filter=${libraryUiState.selectedFilter}," +
            "detailVisible=${detailUiState.isVisible},playerFull=${playerSettings.isFullPlayerVisible}," +
            "miniHidden=${playerSettings.isMiniPlayerHidden},glass=$activeGlassEffectMode"

        // Outer Surface Layout (Avoid Keyboard Inset Clipping)
        // Restore the cleanest top-level Surface layout to completely avoid the truncation and leakage of the floating height and background sampling layer when forced to consume bottom padding due to the keyboard popping up.
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .uiPerformanceTrace(
                    node = "APlayerApp",
                    route = "Root",
                    state = appTraceState
                ),
            color = MaterialTheme.colorScheme.background,
        ) {
            /*
             * Establish Shared Element Layout Context (Shared element boundary setup)
             * Encapsulate overlay views within a SharedTransitionLayout and provide the transition scope
             * via LocalSharedTransitionScope to enable smooth bounds morphing and cover transformations.
             */
            // App Haze Scope (Hoisted above SharedTransitionLayout)
            // LocalHazeState must stay available to every overlay, including those moved outside the
            // shared-element scope, so it is provided on the outer coordinate Box, not inside the scope.
            CompositionLocalProvider(
                com.viel.aplayer.ui.common.theme.LocalHazeState provides hazeState
            ) {
                // Box Layer Decoupling (Prevent Recursive Deadlock Rendering)
                // Full-screen coordinate-alignment container for all overlays. Shared-element surfaces and the
                // non-transition overlays (settings, dialog hosts) are siblings here so their absolute bounds match.
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Shared Element Scope (Narrowed to surfaces that actually morph: Home, Detail, Player, Edit, Search)
                    // Keeping settings and dialog hosts outside this LookaheadScope avoids double-measuring them each frame.
                    SharedTransitionLayout(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CompositionLocalProvider(
                            LocalSharedTransitionScope provides this@SharedTransitionLayout
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                            ) {
                // Navigation Host Layer (Delegate route-level haze source ownership)
                // APlayerNavHost mounts the active route content as the Haze source so HomeAppBar can be rendered as a sibling overlay above that source.
                // Title: Continuous Rendering of Bottom Host
                // Description: Keep drawing bottom navigation host content at all times to enable background blur and gestures.
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
                        playbackViewModel = playbackViewModel,
                        bookmarkViewModel = bookmarkViewModel,
                        settingsViewModel = playerSettingsViewModel,
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
                        // The shell forwards the value only; the Home read model applies the filter to catalog data.
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

                // Mount Detail Route (Register Detail into the app-level haze sampler)
                // Detail uses the shell HazeState again so Search, Edit, and global dialogs keep one backdrop source while the Detail overlay is visible.
                DetailRoute(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    glassEffectMode = activeGlassEffectMode,
                    appHazeState = hazeState,
                    onPlayBook = { bookId ->
                        // Title: Simplify Playback Transition Selection (Use mini-player transition whenever mini-player is visible)
                        // Description: Checks if mini-player is visible before loading the book to decide whether to use openFullPlayerFromMini or openFullPlayerFromDirect.
                        val isMiniPlayerVisible = playbackViewModel.metadataState.value.hasActiveTrack &&
                                !playerSettingsViewModel.settingsState.value.isMiniPlayerHidden
                        playbackViewModel.loadBook(bookId)
                        if (isMiniPlayerVisible) {
                            playerSettingsViewModel.openFullPlayerFromMini()
                        } else {
                            playerSettingsViewModel.openFullPlayerFromDirect()
                        }
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
                        playbackViewModel.closePlayback(bookId)
                        detailViewModel.dismissIfShowing(bookId)
                        libraryViewModel.deleteBook(bookId)
                    },
                    onTransitionIdleChanged = detailTransitionGate::onTransitionIdleChanged
                )


                // Unified Playback Overlay Mounting (Coalesce player and mini-player into one layout component)
                // Mount unified player overlay which internally handles mini-player and full-player states to optimize shell layout.
                PlayerOverlay(
                    playbackViewModel = playbackViewModel,
                    bookmarkViewModel = bookmarkViewModel,
                    settingsViewModel = playerSettingsViewModel,
                    playerActions = playerActions,
                    playerNavigationActions = playerNavigationActions,
                    glassEffectMode = activeGlassEffectMode,
                    appHazeState = hazeState
                )
                
                /*
                 * Mount Edit Overlay (Mount the metadata edit sheet beside detail overlays)
                 * Relocated EditBookRoute back into the app shell coordinate system so edit overlays can animate above details page.
                 */
                EditBookRoute(
                    editViewModel = editViewModel,
                    glassEffectMode = activeGlassEffectMode,
                    hazeState = hazeState,
                    onSaveSuccess = {
                        // Edit Save Success (Reactive Flow Refresh)
                        // After saving successfully, the responsive flow will automatically refresh and redraw the details page via the Room Flow, so there is no need to execute extra UI dirty operations to force a refresh.
                    }
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
                        // Retrieve Cached Detail Book (Fetch pre-mapped detail item for navigation)
                        // Instead of finding and mapping on the fly, retrieve the pre-calculated projection directly by ID.
                        val book = detailBookItems[bookId]
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
                        playbackViewModel.loadBook(bookId)
                    },
                    onNavigateToPlayer = {
                        // Search Direct Playback Open (Keep search playback outside mini motion)
                        // Search currently owns a dedicated Search->Detail transition only; direct
                        // playback should open the full player without claiming the mini source.
                        playerSettingsViewModel.openFullPlayerFromDirect()
                    }
                )
                            }
                        }
                    }
                    // End shared-element scope. The overlays below are siblings of the scope (outside the LookaheadScope, no double measure).

                // Settings Overlay Mount (Let Settings own its page and dialog haze sampling)
                // App-level haze remains available to Home and Search, while SettingsOverlay keeps long-lived settings glass state private to that page.
                SettingsOverlay(
                    settingsViewModel = settingsViewModel,
                    glassEffectMode = activeGlassEffectMode,
                    openDownloadManagementRequest = openDownloadManagementRequest,
                    onOpenDownloadManagementConsumed = onOpenDownloadManagementConsumed
                )

                // Home Add Library Dialog Host (Share Settings add-library dialogs with the empty Home FAB)
                // Hosting this beside the Settings overlay lets Home open the source-type picker directly while reusing SAF, WebDAV, and Audiobookshelf form handling from SettingsDialogHost.
                SettingsDialogHost(
                    controller = homeAddLibraryDialogController,
                    glassEffectMode = activeGlassEffectMode,
                    settingsDialogHazeState = if (activeGlassEffectMode == GlassEffectMode.Haze) hazeState else null,
                    appLanguage = effectiveAppLanguage,
                    // Title: Delegate App Dialog Host Configuration Updates (Route settings changes to handler parameters)
                    onAppLanguageChange = { settingsViewModel.preferencesHandler.updateAppLanguage(it) },
                    webDavConnectionState = webDavConnectionState,
                    onWebDavConnectionTest = { url, username, password, basePath, editingRootId ->
                        settingsViewModel.connectionHandler.testWebDavConnection(url, username, password, basePath, editingRootId)
                    },
                    onResetWebDavConnectionState = {
                        settingsViewModel.connectionHandler.resetWebDavConnectionState()
                    },
                    onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                        settingsViewModel.connectionHandler.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                    },
                    onWebDavRootUpdated = { id, url, username, password, displayName, basePath ->
                        settingsViewModel.updateWebDavRoot(id, url, username, password, displayName, basePath)
                    },
                    absConnectionState = absConnectionState,
                    onAbsConnectionTest = { baseUrl, username, password, editingRootId ->
                        settingsViewModel.connectionHandler.testAbsConnection(baseUrl, username, password, editingRootId)
                    },
                    onResetAbsConnectionState = {
                        settingsViewModel.connectionHandler.resetAbsConnectionState()
                    },
                    onAbsRootSubmitted = { baseUrl, username, password, libraryId, libraryName, editingRootId ->
                        settingsViewModel.connectionHandler.addAbsServerWithPassword(baseUrl, username, password, libraryId, libraryName, editingRootId)
                    },
                    getWebDavCredentials = { credentialId ->
                        settingsViewModel.connectionHandler.getWebDavCredentials(credentialId)
                    },
                    getAbsCredential = { credentialId ->
                        settingsViewModel.connectionHandler.getAbsCredential(credentialId)
                    },
                    onAbsSync = { rootId -> settingsViewModel.connectionHandler.syncAbsRoot(rootId) },
                    onRescan = { settingsViewModel.connectionHandler.triggerRescan() },
                    onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
                    onLaunchSafRootPicker = { homeAddLibraryRootLauncher.launch(null) }
                )

                // Track Unavailable Confirm Dialog (Avoid Interruptions)
                // Secondary confirmation dialog for track unavailability, shown only when the full-screen player is expanded (isFullPlayerVisible) to prevent interrupting user interaction on other screens.
                val trackUnavailableState by playbackViewModel.trackUnavailableDialogState.collectAsStateWithLifecycle()
                // ABS Progress Conflict Dialog State (Surface server-vs-device resume choices at the app shell)
                // The dialog remains hosted near other one-shot player dialogs while all conflict resolution commands stay inside PlayerViewModel.
                val absProgressConflictState by playbackViewModel.absProgressConflictDialogState.collectAsStateWithLifecycle()
                APlayerAppDialogHost(
                    hazeState = hazeState,
                    glassEffectMode = activeGlassEffectMode,
                    isFullPlayerVisible = playerSettings.isFullPlayerVisible,
                    absProgressConflictState = absProgressConflictState,
                    trackUnavailableState = trackUnavailableState,
                    onDismissAbsProgressConflict = { playbackViewModel.dismissAbsProgressConflictDialog() },
                    onAcceptRemoteAbsProgressConflict = { playbackViewModel.acceptRemoteAbsProgressConflict() },
                    onAcceptLocalAbsProgressConflict = { playbackViewModel.acceptLocalAbsProgressConflict() },
                    onDismissTrackUnavailable = { playbackViewModel.dismissTrackUnavailableDialog() },
                    onSkipToNextAvailableTrack = { bookId, queueIndex ->
                        // Skip to Next Available (Trigger ViewModel self-healing from the app-level dialog host)
                        // Keeps the concrete playback mutation in PlayerViewModel while APlayerAppDialogHost owns the confirmation UI.
                        playbackViewModel.skipToNextAvailableTrack(bookId, queueIndex)
                    }
                )
            }
        }
}
}
}
}
