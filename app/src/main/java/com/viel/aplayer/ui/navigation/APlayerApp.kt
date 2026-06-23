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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import com.viel.aplayer.application.library.home.toDetailBookItem
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.shared.settings.ThemeMode
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
import com.viel.aplayer.ui.navigation.shell.DefaultAppFeedbackRenderRouter
import com.viel.aplayer.ui.navigation.shell.dispatch
import com.viel.aplayer.ui.player.BookmarkViewModel
import com.viel.aplayer.ui.player.PlaybackViewModel
import com.viel.aplayer.ui.player.PlayerOverlay
import com.viel.aplayer.ui.player.PlayerSettingsViewModel
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.search.SearchRoute
import com.viel.aplayer.ui.search.SearchViewModel
import com.viel.aplayer.ui.settings.SettingsOverlay
import com.viel.aplayer.ui.settings.SettingsViewModel
import com.viel.aplayer.ui.settings.remote.RemoteConnectionRoute
import com.viel.aplayer.ui.settings.remote.RemoteConnectionViewModel
import dev.chrisbanes.haze.HazeState
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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

    val libraryViewModel: LibraryViewModel = koinViewModel()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

    val detailBookItems = remember(libraryUiState.audiobooks) {
        libraryUiState.audiobooks.associate { it.id to it.toDetailBookItem() }
    }

    val settingsReadModel = koinInject<AppSettingsReadModel>()
    val appEventSink = koinInject<AppEventSink>()
    val appFeedbackRenderRouter = remember {
        DefaultAppFeedbackRenderRouter
    }
    var feedbackDialogMessage by remember { mutableStateOf<FeedbackMessage?>(null) }
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

    val isDynamicColorEnabled = if (libraryUiState.selectedFilter != null) {
        libraryUiState.isDynamicColorEnabled
    } else {
        initialSettings.isDynamicColorEnabled
    }

    val isAmoledEnabled = if (libraryUiState.selectedFilter != null) {
        libraryUiState.isAmoledEnabled
    } else {
        initialSettings.isAmoledEnabled
    }

    val isDarkTheme = when (activeThemeMode) {
        ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val effectiveAppLanguage = AppLocaleController.resolveEffectiveLanguage(context, initialSettings.appLanguage)
    val localizedContext = remember(context, effectiveAppLanguage) {
        AppLocaleController.wrapContext(context, effectiveAppLanguage)
    }
    val localizedConfiguration = localizedContext.resources.configuration
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
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        LocalOnBackPressedDispatcherOwner provides onBackPressedDispatcherOwner,
        LocalLifecycleOwner provides lifecycleOwner,
        LocalViewModelStoreOwner provides viewModelStoreOwner,
        LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration
    ) {
        APlayerTheme(
            darkTheme = isDarkTheme,
            dynamicColor = isDynamicColorEnabled,
            amoled = isAmoledEnabled
        ) {
        val navigationState = rememberNavigationState(
            startRoute = HomeRoute,
            topLevelRoutes = setOf(HomeRoute)
        )
        val navigator = remember { Navigator(navigationState) }
        val currentRoute = navigationState.topLevelRoute

        val playbackViewModel: PlaybackViewModel = koinViewModel()
        val bookmarkViewModel: BookmarkViewModel = koinViewModel()
        val playerSettingsViewModel: PlayerSettingsViewModel = koinViewModel()
        LaunchedEffect(playbackViewModel, playerSettingsViewModel) {
            playbackViewModel.onUndoSeekVisibilityChanged = { visible ->
                playerSettingsViewModel.setUndoSeekVisible(visible)
            }
        }
        val detailViewModel: DetailViewModel = koinViewModel()
        var shouldMountEdit by remember { mutableStateOf(false) }
        var pendingEditBookId by remember { mutableStateOf<String?>(null) }
        var shouldMountSearch by remember { mutableStateOf(false) }
        var pendingSearchQuery by remember { mutableStateOf<String?>(null) }
        var shouldMountSettings by remember { mutableStateOf(openDownloadManagementRequest) }
        var pendingOpenSettingsOverlay by remember { mutableStateOf(openDownloadManagementRequest) }
        var showAddLibraryDialog by remember { mutableStateOf(false) }
        var shouldMountRemoteConnection by remember { mutableStateOf(false) }
        var rootActionsTarget by remember { mutableStateOf<SettingsRootItem?>(null) }
        var rootPendingDelete by remember { mutableStateOf<SettingsRootItem?>(null) }

        val editViewModel: EditBookViewModel? = if (shouldMountEdit) {
            koinViewModel()
        } else {
            null
        }

        val searchViewModel: SearchViewModel? = if (shouldMountSearch) {
            koinViewModel()
        } else {
            null
        }

        val settingsViewModel: SettingsViewModel? = if (shouldMountSettings) {
            koinViewModel()
        } else {
            null
        }

        // Mounted alongside settings so editing/relocating an existing root from settings has a
        // non-null view model, and on its own when the add-library flow is triggered from elsewhere.
        val remoteConnectionViewModel: RemoteConnectionViewModel? =
            if (shouldMountRemoteConnection || shouldMountSettings) {
                koinViewModel()
            } else {
                null
            }

        val libraryRootLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri?.let {
                val vm = remoteConnectionViewModel ?: return@let
                val relocatingRootId = vm.editingSafRootId
                if (relocatingRootId != null) {
                    vm.onSafRootRelocated(relocatingRootId, it)
                    vm.setEditingSafRootId(null)
                } else {
                    vm.onLibraryRootSelected(it)
                }
            }
        }

        LaunchedEffect(editViewModel, pendingEditBookId) {
            val bookId = pendingEditBookId
            if (editViewModel != null && bookId != null) {
                editViewModel.startEdit(bookId)
                pendingEditBookId = null
            }
        }

        LaunchedEffect(searchViewModel, pendingSearchQuery) {
            val query = pendingSearchQuery
            if (searchViewModel != null && query != null) {
                searchViewModel.setVisible(true)
                if (query.isNotBlank()) {
                    searchViewModel.onQueryChange(TextFieldValue(query))
                }
                pendingSearchQuery = null
            }
        }

        LaunchedEffect(openDownloadManagementRequest) {
            if (openDownloadManagementRequest) {
                shouldMountSettings = true
                pendingOpenSettingsOverlay = true
            }
        }

        LaunchedEffect(settingsViewModel, pendingOpenSettingsOverlay) {
            if (settingsViewModel != null && pendingOpenSettingsOverlay) {
                settingsViewModel.setVisible(true)
                pendingOpenSettingsOverlay = false
            }
        }

        val playerSettings by playerSettingsViewModel.settingsState.collectAsStateWithLifecycle()
        val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
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



        val canStartNavigation = rememberNavigationThrottle()

        val hazeState = remember { HazeState() }



        LaunchedEffect(openPlayerOverlayRequest) {
            if (openPlayerOverlayRequest) {
                playerSettingsViewModel.openFullPlayerFromDirect()
                onOpenPlayerOverlayConsumed()
            }
        }

        LaunchedEffect(currentRoute) {
            playerSettingsViewModel.onRouteChanged()
        }


        val currentBookId by playbackViewModel.currentBookId.collectAsStateWithLifecycle()
        val playbackPercent by playbackViewModel.currentPlaybackProgressPercent.collectAsStateWithLifecycle()
        LaunchedEffect(currentBookId, playbackPercent) {
            currentBookId?.let { bookId ->
                if (playbackPercent > 0) {
                    detailViewModel.updatePlaybackProgress(bookId, playbackPercent)
                }
            }
        }

        LaunchedEffect(appEventSink, appFeedbackRenderRouter, playbackViewModel, localizedContext) {
            appEventSink.events.collect { event ->
                appFeedbackRenderRouter.route(event).dispatch(
                    context = localizedContext,
                    onTrackUnavailableDialog = playbackViewModel::showTrackUnavailableDialog,
                    onGenericDialog = { message -> feedbackDialogMessage = message }
                )
            }
        }

        val navigateBack: () -> Unit = remember(navigator) {
            {
                if (canStartNavigation()) {
                    navigator.goBack()
                }
            }
        }

        val playerActions = rememberActions(
            playbackViewModel = playbackViewModel,
            bookmarkViewModel = bookmarkViewModel,
            settingsViewModel = playerSettingsViewModel,
            onDeleteBook = { bookId ->
                playbackViewModel.closePlayback(bookId)
                detailViewModel.dismissIfShowing(bookId)
                libraryViewModel.deleteBook(bookId)
                if (currentRoute != HomeRoute) {
                    navigateBack()
                }
            },
            onOpenRelatedBookDetail = { book ->
                detailBookItems[book.id]?.let { detailBook ->
                    detailTransitionGate.request(
                        DetailOpenRequest(
                            book = detailBook,
                            entrySource = DetailEntrySource.None
                        ),
                        beforeExecute = {
                            playerSettingsViewModel.setFullPlayerVisible(false)
                        }
                    )
                }
            }
        )

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

        val appTraceState = "route=$currentRoute,filter=${libraryUiState.selectedFilter}," +
            "detailVisible=${detailUiState.isVisible},playerFull=${playerSettings.isFullPlayerVisible}," +
            "miniHidden=${playerSettings.isMiniPlayerHidden},glass=$activeGlassEffectMode"

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
            CompositionLocalProvider(
                com.viel.aplayer.ui.common.theme.LocalHazeState provides hazeState
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    SharedTransitionLayout(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CompositionLocalProvider(
                            LocalSharedTransitionScope provides this@SharedTransitionLayout
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    APlayerNavHost(
                        navigationState = navigationState,
                        navigator = navigator,
                        libraryViewModel = libraryViewModel,
                        playbackViewModel = playbackViewModel,
                        bookmarkViewModel = bookmarkViewModel,
                        settingsViewModel = playerSettingsViewModel,
                        detailViewModel = detailViewModel,
                        canStartNavigation = canStartNavigation,
                        appHazeState = hazeState,
                        glassEffectMode = activeGlassEffectMode,
                        onNavigateToSettings = {
                            shouldMountSettings = true
                            pendingOpenSettingsOverlay = true
                        },
                        onNavigateToSearch = {
                            shouldMountSearch = true
                            pendingSearchQuery = ""
                        },
                        onAddLibraryRequested = {
                            shouldMountRemoteConnection = true
                            showAddLibraryDialog = true
                        },
                        onEditBookRequested = { bookId ->
                            shouldMountEdit = true
                            pendingEditBookId = bookId
                        },
                        onOpenDetail = detailTransitionGate::request,
                        onHomeViewStyleSelected = libraryViewModel::setHomeViewStyle,
                        onHomeSortRuleSelected = libraryViewModel::setHomeSortRule,
                        onHomeSortDirectionSelected = libraryViewModel::setHomeSortDirection,
                        onHomeBookStatusFilterSelected = libraryViewModel::setHomeBookStatusFilter
                    )
                }

                DetailRoute(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    glassEffectMode = activeGlassEffectMode,
                    appHazeState = hazeState,
                    onPlayBook = { bookId ->
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
                        shouldMountSearch = true
                        pendingSearchQuery = query
                    },
                    onEditBookRequested = { bookId ->
                        shouldMountEdit = true
                        pendingEditBookId = bookId
                    },
                    onUpdateReadStatus = { bookId, status ->
                        libraryViewModel.updateBookReadStatus(bookId, status)
                    },
                    onForceRegenerate = { bookId ->
                        libraryViewModel.forceRegenerateCoverAndMetadata(bookId)
                    },
                    onDeleteBook = { bookId ->
                        playbackViewModel.closePlayback(bookId)
                        detailViewModel.dismissIfShowing(bookId)
                        libraryViewModel.deleteBook(bookId)
                    },
                    onTransitionIdleChanged = detailTransitionGate::onTransitionIdleChanged
                )


                PlayerOverlay(
                    playbackViewModel = playbackViewModel,
                    bookmarkViewModel = bookmarkViewModel,
                    settingsViewModel = playerSettingsViewModel,
                    playerActions = playerActions,
                    playerNavigationActions = playerNavigationActions,
                    glassEffectMode = activeGlassEffectMode,
                    appHazeState = hazeState
                )

                if (editViewModel != null) {
                    EditBookRoute(
                        editViewModel = editViewModel,
                        glassEffectMode = activeGlassEffectMode,
                        hazeState = hazeState,
                        onSaveSuccess = {
                        }
                    )
                }

                if (searchViewModel != null) {
                    SearchRoute(
                        searchViewModel = searchViewModel,
                        hazeState = hazeState,
                        glassEffectMode = activeGlassEffectMode,
                        activeSearchDetailBookId = if (
                            detailUiState.isVisible &&
                            detailUiState.entrySource == DetailEntrySource.Search
                        ) {
                            detailUiState.book?.bookId
                        } else {
                            null
                        },
                        onNavigateToDetail = { bookId ->
                            val book = detailBookItems[bookId]
                            detailTransitionGate.request(
                                DetailOpenRequest(
                                    book = book,
                                    entrySource = DetailEntrySource.Search
                                ),
                                beforeExecute = {
                                    searchViewModel.setVisible(false)
                                }
                            )
                        },
                        onLoadBook = { bookId ->
                            searchViewModel.setVisible(false)
                            playbackViewModel.loadBook(bookId)
                        },
                        onNavigateToPlayer = {
                            playerSettingsViewModel.openFullPlayerFromDirect()
                        }
                    )
                }
                            }
                        }
                    }

                if (settingsViewModel != null) {
                    SettingsOverlay(
                        settingsViewModel = settingsViewModel,
                        glassEffectMode = activeGlassEffectMode,
                        appHazeState = hazeState,
                        openDownloadManagementRequest = openDownloadManagementRequest,
                        onOpenDownloadManagementConsumed = onOpenDownloadManagementConsumed,
                        onRequestAddLibrary = {
                            shouldMountRemoteConnection = true
                            showAddLibraryDialog = true
                        },
                        onRequestRootActions = { root ->
                            shouldMountRemoteConnection = true
                            rootActionsTarget = root
                        }
                    )
                }

                if (remoteConnectionViewModel != null) {
                    RemoteConnectionRoute(
                        remoteConnectionViewModel = remoteConnectionViewModel,
                        glassEffectMode = activeGlassEffectMode,
                        hazeState = hazeState
                    )
                }

                val trackUnavailableState by playbackViewModel.trackUnavailableDialogState.collectAsStateWithLifecycle()
                val absProgressConflictState by playbackViewModel.absProgressConflictDialogState.collectAsStateWithLifecycle()
                APlayerAppDialogHost(
                    hazeState = hazeState,
                    glassEffectMode = activeGlassEffectMode,
                    feedbackDialogMessage = feedbackDialogMessage,
                    absProgressConflictState = absProgressConflictState,
                    trackUnavailableState = trackUnavailableState,
                    onDismissFeedbackDialog = { feedbackDialogMessage = null },
                    onDismissAbsProgressConflict = { playbackViewModel.dismissAbsProgressConflictDialog() },
                    onAcceptRemoteAbsProgressConflict = { playbackViewModel.acceptRemoteAbsProgressConflict() },
                    onAcceptLocalAbsProgressConflict = { playbackViewModel.acceptLocalAbsProgressConflict() },
                    onDismissTrackUnavailable = { playbackViewModel.dismissTrackUnavailableDialog() },
                    onSkipToNextAvailableTrack = { bookId, queueIndex ->
                        playbackViewModel.skipToNextAvailableTrack(bookId, queueIndex)
                    },
                    showAddLibraryDialog = showAddLibraryDialog,
                    onDismissAddLibrary = { showAddLibraryDialog = false },
                    onAddLibraryPickSaf = {
                        showAddLibraryDialog = false
                        remoteConnectionViewModel?.setEditingSafRootId(null)
                        libraryRootLauncher.launch(null)
                    },
                    onAddLibraryPickWebDav = {
                        showAddLibraryDialog = false
                        remoteConnectionViewModel?.openWebDav()
                    },
                    onAddLibraryPickAbs = {
                        showAddLibraryDialog = false
                        remoteConnectionViewModel?.openAbs()
                    },
                    rootActionsTarget = rootActionsTarget,
                    onDismissRootActions = { rootActionsTarget = null },
                    onEditRoot = { root ->
                        when {
                            root.isSafRoot -> {
                                remoteConnectionViewModel?.setEditingSafRootId(root.rootId)
                                libraryRootLauncher.launch(null)
                            }
                            root.isWebDavRoot -> remoteConnectionViewModel?.openWebDavForEdit(root)
                            else -> remoteConnectionViewModel?.openAbsForEdit(root)
                        }
                    },
                    onSyncRoot = { root -> remoteConnectionViewModel?.syncAbsRoot(root.rootId) },
                    onRescanRoot = { root -> remoteConnectionViewModel?.triggerRescan(root.rootId) },
                    onRequestDeleteRoot = { root ->
                        rootActionsTarget = null
                        rootPendingDelete = root
                    },
                    rootPendingDelete = rootPendingDelete,
                    onConfirmDeleteRoot = {
                        rootPendingDelete?.let { remoteConnectionViewModel?.deleteLibraryRoot(it) }
                        rootPendingDelete = null
                    },
                    onDismissDeleteRoot = { rootPendingDelete = null }
                )
            }
        }
}
}
}
}
