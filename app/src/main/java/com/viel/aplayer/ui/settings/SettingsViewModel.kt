package com.viel.aplayer.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.application.download.ManualDownloadTaskItem
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.application.usecase.BackupManifest
import com.viel.aplayer.di.dependencies.SettingsScreenDependencies
import com.viel.aplayer.event.feedback.DataTransferFeedbackFacts
import com.viel.aplayer.event.feedback.DownloadCacheFeedbackFacts
import com.viel.aplayer.event.feedback.FeedbackFact
import com.viel.aplayer.logger.AbsLogSanitizer
import com.viel.aplayer.shared.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Handler for configuration persistence interactions.
 * Manages reactive settings flows and dispatches business operations.
 */
class SettingsViewModel(
    private val application: android.app.Application,
    private val settingsDependencies: SettingsScreenDependencies
) : ViewModel() {
    private val settingsReadModel = settingsDependencies.settingsReadModel
    private val settingsCommands = settingsDependencies.settingsCommands
    private val formatSettingsRootUseCase = settingsDependencies.formatSettingsRootUseCase
    private val settingsRootReadModel = settingsDependencies.settingsRootReadModel
    private val settingsRootCommands = settingsDependencies.settingsRootCommands
    private val appEventSink = settingsDependencies.appEventSink
    private val exportUserDataUseCase = settingsDependencies.exportUserDataUseCase
    private val importUserDataUseCase = settingsDependencies.importUserDataUseCase
    private val downloadManagementReadModel = settingsDependencies.downloadManagementReadModel
    private val downloadController = settingsDependencies.downloadController

    val preferencesHandler = SettingsPreferencesHandler(
        settingsCommands = settingsCommands,
        scope = viewModelScope,
        app = application
    )

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }

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

    init {
        viewModelScope.launch {
            isVisible.collect { visible ->
                if (visible) {
                    settingsRootCommands.refreshAllRootStatuses()
                }
            }
        }
    }

    fun exportUserData(uri: Uri) {
        viewModelScope.launch {
            val context = application
            val manifest = runCatching {
                exportUserDataUseCase.buildManifest(libraryRootDisplays.value.map { it.locationText })
            }.getOrElse { error ->
                appEventSink.emitFeedback(
                    DataTransferFeedbackFacts.exportFailed(error.message ?: "Unknown error")
                )
                return@launch
            }
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                appEventSink.emitFeedback(DataTransferFeedbackFacts.exportStreamFailed())
                return@launch
            }
            runCatching {
                exportUserDataUseCase.execute(outputStream, manifest)
            }.onSuccess { result ->
                if (result.isSuccess) {
                    appEventSink.emitFeedback(DataTransferFeedbackFacts.exportSucceeded())
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                    appEventSink.emitFeedback(DataTransferFeedbackFacts.exportFailed(msg))
                }
            }.onFailure { error ->
                appEventSink.emitFeedback(
                    DataTransferFeedbackFacts.exportFailed(error.message ?: "")
                )
            }
        }
    }

    suspend fun peekImportManifest(uri: Uri): BackupManifest? {
        val context = application
        return runCatching {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            inputStream.use { importUserDataUseCase.peekManifest(it) }
        }.getOrNull()
    }

    fun importUserData(uri: Uri) {
        viewModelScope.launch {
            val context = application
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                appEventSink.emitFeedback(DataTransferFeedbackFacts.importStreamFailed())
                return@launch
            }
            val manifest = inputStream.use { importUserDataUseCase.peekManifest(it) }
            if (manifest != null && !importUserDataUseCase.isManifestCompatible(manifest)) {
                appEventSink.emitFeedback(
                    DataTransferFeedbackFacts.importVersionIncompatible(
                        backupVersion = manifest.databaseVersion,
                        currentVersion = importUserDataUseCase.currentDatabaseVersion
                    )
                )
                return@launch
            }
            val dataStream = context.contentResolver.openInputStream(uri)
            if (dataStream == null) {
                appEventSink.emitFeedback(DataTransferFeedbackFacts.importStreamFailed())
                return@launch
            }
            runCatching {
                importUserDataUseCase.execute(dataStream)
            }.onSuccess { result ->
                if (result.isSuccess) {
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                    appEventSink.emitFeedback(DataTransferFeedbackFacts.importFailed(msg))
                }
            }.onFailure { error ->
                appEventSink.emitFeedback(
                    DataTransferFeedbackFacts.importFailed(error.message ?: "")
                )
            }
        }
    }

    fun pauseDownload(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.paused(bookId),
            action = { downloadController.pauseDownload(bookId) }
        )
    }

    fun downloadBook(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.queued(bookId),
            action = { downloadController.downloadBook(bookId) }
        )
    }

    fun resumeDownload(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.resumed(bookId),
            action = { downloadController.resumeDownload(bookId) }
        )
    }

    fun deleteDownload(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.deleted(bookId),
            action = { downloadController.deleteDownload(bookId) }
        )
    }

    /**
     * Clears all local offline downloads and task list items.
     * Triggers the cache maintenance commands to evict all manually cached audio files and drops
     * the download queue states from persistence, reporting outcome to the app feedback sink.
     */
    fun deleteAllDownloads() {
        runDownloadCommand(
            bookId = null,
            successFact = DownloadCacheFeedbackFacts.deletedAll(),
            action = { settingsDependencies.cacheMaintenanceCommands.deleteAllManualDownloads() }
        )
    }

    fun onDownloadNotificationPermissionDenied() {
        appEventSink.emitFeedback(DownloadCacheFeedbackFacts.notificationPermissionDenied())
    }

    private fun runDownloadCommand(
        bookId: String?,
        successFact: FeedbackFact,
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                action()
            }.onSuccess {
                appEventSink.emitFeedback(successFact)
            }.onFailure { error ->
                appEventSink.emitFeedback(
                    DownloadCacheFeedbackFacts.commandFailed(bookId, AbsLogSanitizer.compact(error.message))
                )
            }
        }
    }
}
