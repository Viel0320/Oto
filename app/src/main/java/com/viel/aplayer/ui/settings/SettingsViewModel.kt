package com.viel.aplayer.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.application.download.CacheStatistics
import com.viel.aplayer.application.download.ManualDownloadTaskItem
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.logger.AbsLogSanitizer
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
    // Title: Settings Abstractions Binding (Bind settings ViewModel to read and command abstractions)
    // Decouples ViewModel from the concrete data repository class.
    private val settingsReadModel = settingsDependencies.settingsReadModel
    private val settingsCommands = settingsDependencies.settingsCommands
    // Title: Bind FormatSettingsRootUseCase (Inject formatting usecase for display snapshot formatting)
    // Avoids static utility dependencies and decouples DB entities from SettingsViewModel.
    private val formatSettingsRootUseCase = settingsDependencies.formatSettingsRootUseCase
    // Settings Root Scene Interfaces
    private val settingsRootReadModel = settingsDependencies.settingsRootReadModel
    private val settingsRootCommands = settingsDependencies.settingsRootCommands
    // Settings Query Use Cases
    private val settingsLibraryMaintenanceUseCase = settingsDependencies.settingsLibraryMaintenanceUseCase
    private val appEventSink = settingsDependencies.appEventSink
    private val deleteLibraryRootUseCase = settingsDependencies.deleteLibraryRootUseCase
    private val exportUserDataUseCase = settingsDependencies.exportUserDataUseCase
    private val importUserDataUseCase = settingsDependencies.importUserDataUseCase
    private val downloadManagementReadModel = settingsDependencies.downloadManagementReadModel
    private val downloadController = settingsDependencies.downloadController
    private val cacheStatisticsProvider = settingsDependencies.cacheStatisticsProvider
    private val cacheMaintenanceCommands = settingsDependencies.cacheMaintenanceCommands

    // Title: Initialize Preferences Handler (Pass settings write interface to preferences handler)
    val preferencesHandler = SettingsPreferencesHandler(
        settingsCommands = settingsCommands,
        scope = viewModelScope,
        app = getApplication()
    )

    val connectionHandler = SettingsConnectionHandler(
        absSettingsConnectionUseCase = settingsDependencies.absSettingsConnectionUseCase,
        testWebDavConnectionUseCase = settingsDependencies.testWebDavConnectionUseCase,
        settingsQueryUseCase = settingsDependencies.settingsQueryUseCase,
        settingsRootCommands = settingsRootCommands,
        // Title: Pass formatting UseCase (Wire formatSettingsRootUseCase into the connection handler)
        formatSettingsRootUseCase = formatSettingsRootUseCase,
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

    /** Exposed settings flow */
    val settingsState: StateFlow<AppSettings> = settingsReadModel.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    val libraryRootDisplays: StateFlow<List<SettingsRootItem>> = settingsRootReadModel
        .observeRootSnapshots()
        .map { snapshots ->
            // Title: Map Snapshots with UseCase (Delegate formatting logic to FormatSettingsRootUseCase)
            // Removes direct imports of AudiobookSchema and decouples model mapping completely.
            snapshots.map(formatSettingsRootUseCase::formatSnapshot)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val downloadTasks: StateFlow<List<ManualDownloadTaskItem>> = downloadManagementReadModel
        .observeManualDownloadTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _cacheStatistics = MutableStateFlow(CacheStatistics(0L, 0))
    val cacheStatistics: StateFlow<CacheStatistics> = _cacheStatistics.asStateFlow()

    init {
        viewModelScope.launch {
            isVisible.collect { visible ->
                if (visible) {
                    settingsRootCommands.refreshAllRootStatuses()
                    refreshCacheStatistics()
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

    // Title: Export User Data (Trigger the export usecase to package databases and settings to SAF selected ZIP file)
    // Opens the destination output stream and dispatches the task to IO dispatcher, broadcasting status via appEventSink.
    fun exportUserData(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                // Title: Export Stream Open Failure (Display localized error message when output stream fails)
                // Replaces rawText error message with resource-backed FeedbackMessage.Resource to satisfy localization test rules.
                appEventSink.showToast(FeedbackMessage.Resource(R.string.feedback_settings_export_stream_failed))
                return@launch
            }
            runCatching {
                exportUserDataUseCase.execute(outputStream)
            }.onSuccess { result ->
                if (result.isSuccess) {
                    appEventSink.showToast(FeedbackMessage.Resource(R.string.feedback_settings_export_success))
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                    appEventSink.showToast(FeedbackMessage.Resource(R.string.feedback_settings_export_failed, listOf(msg)))
                }
            }.onFailure { error ->
                appEventSink.showToast(FeedbackMessage.Resource(R.string.feedback_settings_export_failed, listOf(error.message ?: "")))
            }
        }
    }

    // Title: Import User Data and Restart (Execute the restore usecase and perform a clean process restart)
    // Runs overwrite actions, then terminates active components, schedules main activity relaunch, and exits the process.
    fun importUserData(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                // Title: Import Stream Open Failure (Display localized error message when input stream fails)
                // Replaces rawText error message with resource-backed FeedbackMessage.Resource to satisfy localization test rules.
                appEventSink.showToast(FeedbackMessage.Resource(R.string.feedback_settings_import_stream_failed))
                return@launch
            }
            runCatching {
                importUserDataUseCase.execute(inputStream)
            }.onSuccess { result ->
                if (result.isSuccess) {
                    // Trigger Application Restart
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                    appEventSink.showToast(FeedbackMessage.Resource(R.string.feedback_settings_import_failed, listOf(msg)))
                }
            }.onFailure { error ->
                appEventSink.showToast(FeedbackMessage.Resource(R.string.feedback_settings_import_failed, listOf(error.message ?: "")))
            }
        }
    }

    fun refreshCacheStatistics() {
        viewModelScope.launch {
            runCatching {
                cacheStatisticsProvider.snapshot()
            }.onSuccess { statistics ->
                _cacheStatistics.value = statistics
            }.onFailure { error ->
                appEventSink.showToast(FeedbackMessages.downloadCacheCommandFailed(AbsLogSanitizer.compact(error.message)))
            }
        }
    }

    fun pauseDownload(bookId: String) {
        runDownloadCommand(
            successMessage = FeedbackMessages.downloadCachePaused(),
            action = { downloadController.pauseDownload(bookId) }
        )
    }

    fun downloadBook(bookId: String) {
        runDownloadCommand(
            successMessage = FeedbackMessages.downloadCacheQueued(),
            action = { downloadController.downloadBook(bookId) }
        )
    }

    fun resumeDownload(bookId: String) {
        runDownloadCommand(
            successMessage = FeedbackMessages.downloadCacheResumed(),
            action = { downloadController.resumeDownload(bookId) }
        )
    }

    fun deleteDownload(bookId: String) {
        runDownloadCommand(
            successMessage = FeedbackMessages.downloadCacheDeleted(),
            action = { downloadController.deleteDownload(bookId) }
        )
    }

    fun clearManualDownloadCache() {
        runDownloadCommand(
            successMessage = FeedbackMessages.manualDownloadCacheCleared(),
            action = { cacheMaintenanceCommands.deleteAllManualDownloads() }
        )
    }

    fun onDownloadNotificationPermissionDenied() {
        appEventSink.showToast(FeedbackMessages.downloadNotificationPermissionDenied())
    }

    // Title: Run Download Management Command (Apply manual cache operations from settings pages)
    // Uses resource-backed feedback and refreshes cache totals after the command has updated Media3/Room state.
    private fun runDownloadCommand(
        successMessage: FeedbackMessage,
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                action()
            }.onSuccess {
                appEventSink.showToast(successMessage)
                refreshCacheStatistics()
            }.onFailure { error ->
                appEventSink.showToast(FeedbackMessages.downloadCacheCommandFailed(AbsLogSanitizer.compact(error.message)))
            }
        }
    }
}
