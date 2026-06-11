package com.viel.aplayer.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.logger.AbsSettingsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings view model (Handler for configuration persistence interactions)
 * Manages reactive settings flows and dispatches business operations.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    // Settings Screen Dependency View (Resolve only settings-page operations and feedback)
    private val settingsDependencies = APlayerApplication.getSettingsScreenDependencies(application)
    private val settingsRepository = settingsDependencies.settingsRepository
    // Settings Root Scene Interfaces
    private val settingsRootReadModel = settingsDependencies.settingsRootReadModel
    private val settingsRootCommands = settingsDependencies.settingsRootCommands
    // Settings Query Use Cases
    private val settingsLibraryMaintenanceUseCase = settingsDependencies.settingsLibraryMaintenanceUseCase
    private val appEventSink = settingsDependencies.appEventSink
    private val deleteLibraryRootUseCase = settingsDependencies.deleteLibraryRootUseCase

    // Title: Initialize Delegated Handlers (Instantiate separate modules to manage preferences and connections separately)
    val preferencesHandler = SettingsPreferencesHandler(
        settingsRepository = settingsRepository,
        scope = viewModelScope,
        app = getApplication()
    )

    val connectionHandler = SettingsConnectionHandler(
        absSettingsConnectionUseCase = settingsDependencies.absSettingsConnectionUseCase,
        webDavConnectionTester = settingsDependencies.webDavConnectionTester,
        settingsQueryUseCase = settingsDependencies.settingsQueryUseCase,
        settingsRootCommands = settingsRootCommands,
        appEventSink = appEventSink,
        scope = viewModelScope,
        app = getApplication()
    )

    // Settings Overlay Visibility State
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }

    // Title: Expose Connection Handlers States (Retrieve reactive UI states directly from ConnectionHandler)
    val absConnectionState: StateFlow<AbsConnectionUiState> = connectionHandler.absConnectionState
    val webDavConnectionState: StateFlow<WebDavConnectionUiState> = connectionHandler.webDavConnectionState
    val absSyncConfirmationState: StateFlow<AbsSyncConfirmationState?> = connectionHandler.absSyncConfirmationState

    /** Exposed settings flow */
    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    val libraryRootDisplays: StateFlow<List<SettingsRootItem>> = settingsRootReadModel
        .observeRootSnapshots()
        .map { snapshots ->
            // Title: Map Snapshots to UI Items (Delegate formatting logic to SettingsFormatter for representation mapping)
            snapshots.map { snapshot ->
                val isAbsRoot = snapshot.sourceType == AudiobookSchema.LibrarySourceType.ABS
                SettingsRootItem(
                    rootId = snapshot.rootId,
                    sourceType = snapshot.sourceType,
                    sourceUri = snapshot.sourceUri,
                    basePath = snapshot.basePath,
                    credentialId = snapshot.credentialId,
                    displayName = snapshot.displayName,
                    title = SettingsFormatter.resolveLibraryRootTitle(snapshot),
                    statusText = SettingsFormatter.resolveLibraryRootStatusText(snapshot, getApplication()),
                    locationText = SettingsFormatter.resolveLibraryRootLocation(snapshot),
                    selectedLibraryText = if (isAbsRoot) snapshot.displayName.ifBlank { snapshot.basePath } else null,
                    lastSyncText = SettingsFormatter.formatLibraryRootSyncTime(
                        timestampMs = if (isAbsRoot) snapshot.absLastFullSyncAt else snapshot.lastScannedAt.takeIf { it > 0L },
                        notSyncedText = getApplication<Application>().getString(R.string.settings_library_not_synced)
                    ),
                    importedBookCount = snapshot.importedBookCount,
                    lastError = snapshot.absLastError?.let(SettingsFormatter::redactAbsError)
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            isVisible.collect { visible ->
                if (visible) {
                    settingsRootCommands.refreshAllRootStatuses()
                }
            }
        }
    }

    fun onLibraryRootSelected(uri: Uri) {
        settingsRootCommands.addLocalRootAndScheduleSync(uri)
    }

    /**
     * Handle SAF root relocation (To update local library path and clear incremental cache)
     */
    fun onSafRootRelocated(id: String, newUri: Uri) {
        viewModelScope.launch {
            runCatching {
                settingsLibraryMaintenanceUseCase.updateSafRootAndScheduleSync(id, newUri)
            }.onSuccess {
                appEventSink.showToast(FeedbackMessages.settingsLocalLibraryRelocated())
            }.onFailure { error ->
                com.viel.aplayer.logger.ScanWorkflowLogger.error("onSafRootRelocated failed", error)
                appEventSink.showToast(FeedbackMessages.settingsLocalLibraryRelocationFailed(error.message))
            }
        }
    }

    // Title: Update WebDAV Configuration (Modify remote server connection directory credentials)
    fun updateWebDavRoot(
        id: String,
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        viewModelScope.launch {
            runCatching {
                settingsLibraryMaintenanceUseCase.updateWebDavRootAndScheduleSync(
                    id = id,
                    url = url,
                    username = username,
                    password = password,
                    displayName = displayName,
                    basePath = basePath
                )
            }.onSuccess {
                appEventSink.showToast(FeedbackMessages.settingsWebDavUpdated())
            }.onFailure { error ->
                appEventSink.showToast(FeedbackMessages.settingsWebDavUpdateFailed(error.message))
            }
        }
    }

    // Title: Deregister Library Root (Shutdown playback, clear DB rows, and release Android permissions)
    fun deleteLibraryRoot(root: SettingsRootItem) {
        viewModelScope.launch {
            AbsSettingsLogger.logDeleteServerStart(rootId = root.rootId, sourceType = root.sourceType)
            val playbackWasStopped = deleteLibraryRootUseCase.invoke(root.rootId)
            AbsSettingsLogger.logDeleteServerFinished(rootId = root.rootId, playbackStopped = playbackWasStopped)
            appEventSink.showToast(FeedbackMessages.settingsLibraryRootRemoved(playbackWasStopped))
        }
    }
}
